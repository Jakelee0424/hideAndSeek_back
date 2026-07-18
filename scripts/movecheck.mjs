/**
 * 달리기(sprint)·점프(jump) 서버 검증. 사람 하나로 접속해 스냅샷만 보고 판정한다.
 *
 *   node --experimental-websocket scripts/movecheck.mjs [포트]
 *
 * ⚠️ botcheck.mjs와 같은 이유로 --experimental-websocket이 필수다(Node 20).
 *
 * 판별 원리
 *  - 속도: 스냅샷 간 이동량 / 시간. 걷기와 달리기의 **최대 순간속도** 비가 배수와 맞는지 본다.
 *    감옥 맵은 벽이 많아 한 방향으로만 밀면 막혀서 0이 나올 수 있다 → 관측 구간 동안 진행
 *    방향을 한 바퀴 돌리고 그중 최댓값을 쓴다. 한 방향이라도 트여 있으면 제 속도가 찍힌다.
 *  - 점프: y(지면 위 높이)가 0을 벗어났다가 다시 0으로 돌아오는지, 정점이 이론값과 맞는지 본다.
 *    y는 서버가 보내주는 값이라 이게 오르면 "남에게도 점프가 보인다"는 뜻이다.
 */

const PORT = process.argv[2] ?? '8080';
const ROOM = 'movecheck';
const ME = 'human-1';

const INPUT_HZ_MS = 100; // input-timeout-ms=500 보다 촘촘히
const PHASE_MS = 4000; // 걷기/달리기 각 관측 시간
const JUMP_MS = 3000;

/** application.yml과 일치해야 한다. 어긋나면 이 스크립트가 먼저 틀렸다고 말해준다. */
const EXPECT = {
  speed: 6.0,
  sprintMultiplier: 1.8,
  jumpSpeed: 6.0,
  gravity: 18.0,
  tickMs: 50,
};

/**
 * 점프 정점의 기대값. 연속식 v²/2g(=1.00m)를 쓰면 안 된다 — 서버는 50ms 고정 스텝
 * 오일러 적분이라 체계적으로 낮게 나온다(6.0/18.0 기준 0.855m). 같은 식으로 시뮬레이션해 구한다.
 */
function expectedPeak() {
  const dt = EXPECT.tickMs / 1000;
  let vy = EXPECT.jumpSpeed;
  let y = 0;
  let peak = 0;
  for (let i = 0; i < 200 && (y > 0 || vy > 0); i++) {
    vy -= EXPECT.gravity * dt;
    y += vy * dt;
    if (y <= 0) break;
    peak = Math.max(peak, y);
  }
  return peak;
}
const EXPECT_PEAK = expectedPeak();

// ── raw STOMP (botcheck.mjs와 동일) ──────────────────────────────────────────

function frame(command, headers = {}, body = '') {
  const h = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n');
  return `${command}\n${h}\n\n${body}\0`;
}

function* parseFrames(buf) {
  let i;
  while ((i = buf.indexOf('\0')) >= 0) {
    const raw = buf.slice(0, i);
    buf = buf.slice(i + 1);
    const text = raw.replace(/^\n+/, '');
    if (!text) continue;
    const sep = text.indexOf('\n\n');
    const head = sep < 0 ? text : text.slice(0, sep);
    const body = sep < 0 ? '' : text.slice(sep + 2);
    yield { command: head.split('\n')[0], body, rest: buf };
  }
  return buf;
}

// ── 상태 ──────────────────────────────────────────────────────────────────────

/** 'walk' | 'sprint' | 'jump' | 'done' */
let phase = 'walk';
let phaseStartedAt = 0;
let seq = 0;
let prev = null; // { x, z, t }
let sawYField = false;

/** 단계별 per-tick 속도 표본. 판정은 아래 topMedian으로 한다. */
const samples = { walk: [], sprint: [] };

/**
 * "막히지 않은 구간의 속도" 추정치.
 *
 * 단순 최댓값은 못 쓴다 — 서버가 좌표를 소수 2자리로 반올림해 보내므로(페이로드 경량화)
 * 0.3m 스텝에 ±0.005의 노이즈가 실려 약 ±2.4%가 흔들리는데, max는 그 양(+)쪽 꼬리만
 * 골라 체계적으로 부풀린다(실측 6.00 → 6.18).
 * 그렇다고 전체 중앙값도 못 쓴다 — 벽에 막힌 구간이 섞여 낮아진다.
 * → 최댓값의 90% 이상인 표본(=트인 방향으로 제 속도가 난 구간)만 남기고 그 중앙값을 쓴다.
 */
function topMedian(arr) {
  if (arr.length === 0) return 0;
  const peak = Math.max(...arr);
  const top = arr.filter((v) => v >= peak * 0.9).sort((a, b) => a - b);
  return top[Math.floor(top.length / 2)];
}
const jump = { peakY: 0, leftGround: false, landed: false };

const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
let buf = '';

ws.addEventListener('error', (e) => {
  console.error(`✗ 접속 실패 (ws://localhost:${PORT}/ws) — 서버가 떠 있나?`, e.message ?? '');
  process.exit(1);
});

ws.addEventListener('open', () => {
  ws.send(frame('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0' }));
});

ws.addEventListener('message', (ev) => {
  buf += typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8');
  for (const f of parseFrames(buf)) {
    buf = f.rest;
    if (f.command === 'CONNECTED') onConnected();
    else if (f.command === 'MESSAGE') onSnapshot(JSON.parse(f.body));
    else if (f.command === 'ERROR') {
      console.error('✗ STOMP ERROR:', f.body);
      process.exit(1);
    }
  }
});

function onConnected() {
  ws.send(frame('SUBSCRIBE', { id: 'sub-0', destination: `/topic/rooms/${ROOM}/state` }));
  ws.send(frame('SEND', { destination: `/app/rooms/${ROOM}/join`, 'content-type': 'application/json' },
    JSON.stringify({ id: ME, nick: '이동검증' })));
  phaseStartedAt = Date.now();
  console.log(`● 방 '${ROOM}' 입장. 걷기 ${PHASE_MS / 1000}s → 달리기 ${PHASE_MS / 1000}s → 점프 ${JUMP_MS / 1000}s`);
  setInterval(drive, INPUT_HZ_MS);
}

/** 현재 단계에 맞는 입력을 보낸다. 방향은 관측 구간 동안 한 바퀴 회전(벽 회피). */
function drive() {
  const elapsed = Date.now() - phaseStartedAt;
  advancePhase(elapsed);
  if (phase === 'done') return;

  const moving = phase !== 'jump';
  // 단계 시간에 비례해 heading을 0→2π로 돌린다.
  const heading = (elapsed / PHASE_MS) * Math.PI * 2;
  const mx = moving ? Math.sin(heading) : 0;
  const mz = moving ? Math.cos(heading) : 0;

  seq += 1;
  ws.send(frame('SEND', { destination: `/app/rooms/${ROOM}/input`, 'content-type': 'application/json' },
    JSON.stringify({
      seq,
      move: { x: mx, y: 0, z: mz },
      rotationY: Math.atan2(mx, mz),
      sprint: phase === 'sprint',
      jump: phase === 'jump',
    })));
}

function advancePhase(elapsed) {
  const limit = phase === 'jump' ? JUMP_MS : PHASE_MS;
  if (elapsed < limit) return;
  if (phase === 'walk') {
    phase = 'sprint';
    phaseStartedAt = Date.now();
    prev = null; // 단계 경계의 이동량은 섞지 않는다
    console.log('  · 걷기 구간 끝 → 달리기');
  } else if (phase === 'sprint') {
    phase = 'jump';
    phaseStartedAt = Date.now();
    prev = null;
    console.log('  · 달리기 구간 끝 → 제자리 점프');
  } else if (phase === 'jump') {
    phase = 'done';
    report();
  }
}

function onSnapshot(snap) {
  const me = (snap.states ?? []).find((s) => s.id === ME);
  if (!me) return;
  if (me.y !== undefined) sawYField = true;
  const y = me.y ?? 0;

  if (phase === 'jump') {
    if (y > 0.02) {
      jump.leftGround = true;
      jump.peakY = Math.max(jump.peakY, y);
    } else if (jump.leftGround) {
      jump.landed = true;
    }
  }

  // dt는 스냅샷 "도착 시각"이 아니라 서버 tick 번호로 잰다. 도착 시각을 쓰면 네트워크·스케줄러
  // 지터가 그대로 속도로 둔갑해 부풀려진다(실측: 걷기가 6.0 대신 7.4로 나왔다).
  if (prev && (phase === 'walk' || phase === 'sprint')) {
    const dTick = snap.tick - prev.tick;
    if (dTick > 0) {
      const dt = (dTick * EXPECT.tickMs) / 1000;
      samples[phase].push(Math.hypot(me.x - prev.x, me.z - prev.z) / dt);
    }
  }
  prev = { x: me.x, z: me.z, tick: snap.tick };
}

function report() {
  const expectSprint = EXPECT.speed * EXPECT.sprintMultiplier;
  const walk = topMedian(samples.walk);
  const sprint = topMedian(samples.sprint);
  const ratio = walk > 0 ? sprint / walk : 0;

  console.log('\n──────── 결과 ────────');
  console.log(`걷기   ${walk.toFixed(2)} m/s   (기대 ${EXPECT.speed.toFixed(2)}, 표본 ${samples.walk.length})`);
  console.log(`달리기 ${sprint.toFixed(2)} m/s   (기대 ${expectSprint.toFixed(2)}, 표본 ${samples.sprint.length})`);
  console.log(`배수   ${ratio.toFixed(2)}×      (기대 ${EXPECT.sprintMultiplier.toFixed(2)}×)`);
  console.log(`점프 정점 ${jump.peakY.toFixed(2)} m  (기대 ${EXPECT_PEAK.toFixed(2)})`);
  console.log(`y 필드 수신 ${sawYField ? '있음' : '없음 ⚠️ (PlayerTick에 y가 안 실렸다)'}`);

  // tick 기반 + topMedian이라 남는 오차는 좌표 반올림뿐이다.
  const okWalk = Math.abs(walk - EXPECT.speed) <= 0.1;
  const okSprint = Math.abs(sprint - expectSprint) <= 0.15;
  const okJump = jump.leftGround && jump.landed && Math.abs(jump.peakY - EXPECT_PEAK) <= 0.05;

  console.log('\n──────── 판정 ────────');
  console.log(okWalk ? '✅ 걷기 속도 정상' : '❌ 걷기 속도가 기대와 다르다 (벽에 갇혔거나 speed 설정 불일치)');
  console.log(okSprint ? '✅ 달리기 반영됨 — 서버가 sprint 의도를 받아 배수를 곱했다'
                       : '❌ 달리기 미반영 — InputMessage.sprint 또는 sprint-multiplier를 볼 것');
  if (!jump.leftGround) console.log('❌ 점프 안 함 — y가 한 번도 0을 벗어나지 않았다');
  else if (!jump.landed) console.log('❌ 착지 안 함 — 공중에 떠 있다(중력 적분/접지 판정을 볼 것)');
  else if (!okJump) console.log(`❌ 점프 높이가 기대와 다르다 (${jump.peakY.toFixed(2)} vs ${EXPECT_PEAK.toFixed(2)})`);
  else console.log('✅ 점프 정상 — 떴다가 지면으로 돌아왔고 정점도 이론값과 맞는다');

  ws.close();
  process.exit(okWalk && okSprint && okJump ? 0 : 1);
}
