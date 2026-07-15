/**
 * AI 봇 동작 검증. 사람 플레이어 하나로 접속해 스냅샷을 받아보고 봇의 움직임으로 층을 판정한다.
 *
 *   node --experimental-websocket scripts/botcheck.mjs [포트]
 *
 * 서버는 봇 LLM을 켜서 띄워야 한다(기본 off):
 *   ./gradlew bootRun --args="--game.bot.llm.enabled=true" -Dorg.gradle.java.installations.paths=...
 *
 * ⚠️ 의존성 없이 raw STOMP를 직접 만든다. 전역 WebSocket이 Node 20에선 플래그 뒤에 있어
 *    --experimental-websocket이 필수다(빼면 "WebSocket is not defined"). Node 22+면 불필요.
 *
 * 판별 원리 — 스크립트 층은 solvable=true인 POI만 고른다(Interactables.nearestUnsolved).
 * 따라서 봇은 스크립트만으로는 note-1(쪽지)에 **절대 가지 않는다**. 봇이 note-1에 닿았다면
 * 그 목표는 LLM이 낸 것(GOTO_NOTE)이다. 이게 LLM 느린 층이 살아있다는 유일한 외부 증거다.
 *
 * FOLLOW_PLAYER는 사람을 POI에서 먼 구석(9,9)까지 걸어가 세워두고 본다. 봇 스폰(0,0)과
 * 사람 스폰(1,0)이 붙어 있어 생기는 우연한 근접을 배제하기 위함이다(구 botcheck3의 한계).
 */

const PORT = process.argv[2] ?? '8080';
const ROOM = 'botcheck';
const ME = 'human-1';
const BOT = 'bot-1';

const DURATION_MS = 30_000;
const INPUT_HZ_MS = 100; // 서버 input-timeout-ms=500 → 이보다 촘촘히 보내야 계속 걷는다

/** Interactables.java와 일치해야 함. 바뀌면 여기도 반영. */
const POIS = [
  { id: 'lockbox-1', x: 5, z: -4, solvable: true },
  { id: 'door-1', x: -8, z: 0, solvable: true },
  { id: 'note-1', x: 0, z: 6, solvable: false },
];

/** 사람을 세워둘 자리. 어떤 POI와도 9m 이상 떨어져야 FOLLOW 판정이 의미를 갖는다. */
const PARK = { x: 9, z: 9 };
const ARRIVE_R = 0.5;

const dist = (ax, az, bx, bz) => Math.hypot(ax - bx, az - bz);

// ── raw STOMP ────────────────────────────────────────────────────────────────

function frame(command, headers = {}, body = '') {
  const h = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n');
  return `${command}\n${h}\n\n${body}\0`;
}

/** 수신 버퍼 → 프레임 단위로 쪼갠다. STOMP 프레임은 NUL로 끝난다. */
function* parseFrames(buf) {
  let i;
  while ((i = buf.indexOf('\0')) >= 0) {
    const raw = buf.slice(0, i);
    buf = buf.slice(i + 1);
    const text = raw.replace(/^\n+/, ''); // 하트비트로 붙는 개행 제거
    if (!text) continue;
    const sep = text.indexOf('\n\n');
    const head = sep < 0 ? text : text.slice(0, sep);
    const body = sep < 0 ? '' : text.slice(sep + 2);
    yield { command: head.split('\n')[0], body, rest: buf };
  }
  return buf;
}

// ── 측정 ──────────────────────────────────────────────────────────────────────

const min = {
  ...Object.fromEntries(POIS.map((p) => [p.id, Infinity])),
  human: Infinity,
};
let snapshots = 0;
let botSeen = 0;
let roster = null;
let botPath = []; // 봇 궤적(듬성듬성) — 무한 왕복(이슈 1) 눈으로 보기용
let parked = false; // 사람이 PARK에 도착했나
let parkedSnapshots = 0;

const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
let buf = '';
let seq = 0;
let me = { x: 1, z: 0 }; // 첫 스냅샷 전까지의 추정 스폰

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
    JSON.stringify({ id: ME, nick: '검증' })));
  console.log(`● 방 '${ROOM}' 입장. ${DURATION_MS / 1000}초간 관측한다...`);
  setInterval(walk, INPUT_HZ_MS);
  setTimeout(report, DURATION_MS);
}

/** 사람을 PARK까지 걸어보낸 뒤 멈춘다(입력을 끊으면 서버가 500ms 뒤 정지로 본다). */
function walk() {
  const dx = PARK.x - me.x;
  const dz = PARK.z - me.z;
  const len = Math.hypot(dx, dz);
  if (len <= ARRIVE_R) return;
  seq += 1;
  ws.send(frame('SEND', { destination: `/app/rooms/${ROOM}/input`, 'content-type': 'application/json' },
    JSON.stringify({ seq, move: { x: dx / len, y: 0, z: dz / len }, rotationY: Math.atan2(dx, dz) })));
}

function onSnapshot(snap) {
  snapshots += 1;
  if (snap.roster) roster = snap.roster;

  const states = snap.states ?? [];
  const human = states.find((s) => s.id === ME);
  if (human) me = { x: human.x, z: human.z };

  if (human && dist(human.x, human.z, PARK.x, PARK.z) <= ARRIVE_R) parked = true;

  const bot = states.find((s) => s.id === BOT);
  if (!bot) return;
  botSeen += 1;

  for (const p of POIS) {
    min[p.id] = Math.min(min[p.id], dist(bot.x, bot.z, p.x, p.z));
  }
  // 사람이 PARK에 선 뒤부터만 잰다. 걸어가는 도중을 포함하면 스폰(0,0)/(1,0)이 붙어 있어
  // 봇이 가만 있어도 1m가 찍힌다 — 구 botcheck3의 0.64m가 이 착시였다.
  if (human && parked) {
    parkedSnapshots += 1;
    min.human = Math.min(min.human, dist(bot.x, bot.z, human.x, human.z));
  }
  if (snapshots % 20 === 0) botPath.push(`(${bot.x.toFixed(1)},${bot.z.toFixed(1)})`);
}

function report() {
  const f = (v) => (v === Infinity ? '  —  ' : `${v.toFixed(2)}m`);
  console.log('\n──────── 결과 ────────');
  console.log(`스냅샷 ${snapshots} · 봇 관측 ${botSeen}`);
  console.log(`로스터: ${roster ? roster.map((r) => `${r.id}(${r.nick})`).join(', ') : '못 받음 ⚠️'}`);
  console.log('\n봇의 최소 접근 거리:');
  for (const p of POIS) {
    console.log(`  ${p.id.padEnd(10)} ${f(min[p.id])}   ${p.solvable ? '(스크립트도 감)' : '★ LLM만 감'}`);
  }
  console.log(`  ${'사람'.padEnd(9)} ${f(min.human)}   (PARK ${PARK.x},${PARK.z} 정차 후 ${parkedSnapshots}스냅샷 기준)`);
  console.log(`\n봇 궤적: ${botPath.join(' → ') || '없음'}`);

  const llmAlive = min['note-1'] <= 1.5;
  const follow = parked && min.human <= 2.0;
  console.log('\n──────── 판정 ────────');
  if (botSeen === 0) {
    console.log('✗ 봇이 스냅샷에 없다. spawnBot/isEmpty를 볼 것.');
  } else if (llmAlive) {
    console.log('✅ LLM 느린 층 동작 — 봇이 note-1에 도달했다(스크립트 층은 쪽지에 가지 않는다).');
  } else {
    console.log('❌ LLM 층 미확인 — note-1 근처에 못 갔다. 봇은 스크립트로만 돈 듯하다.');
    console.log('   서버 로그의 "봇 계획:" / "봇 계획 실패(...)" 라인을 볼 것.');
  }
  console.log(follow ? '✅ FOLLOW_PLAYER 정황 — 먼 구석의 사람에게 접근했다.'
                     : '· FOLLOW_PLAYER 미관측(이번 판단에 안 나왔을 뿐일 수 있다).');
  ws.close();
  process.exit(llmAlive ? 0 : 1);
}
