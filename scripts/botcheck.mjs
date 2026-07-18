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

/**
 * Interactables.java와 일치해야 함. 바뀌면 여기도 반영.
 *
 * ⚠️ 감옥 맵(2026-07-18)부터 세 지점이 모두 **감방 안**이고 감방문은 닫힌 채 시작한다.
 *    사람이 F로 문을 열어주기 전까지 봇은 자기 감방 밖으로 못 나가므로,
 *    "note-1 도달 = LLM 동작"이라는 원래 판별은 문이 열린 상황에서만 성립한다.
 *    문이 닫힌 채로도 확인할 수 있는 것은 아래의 "봇이 움직이는가"다.
 */
const POIS = [
  { id: 'lockbox-1', x: -10, z: 8, solvable: true },  // 1호실
  { id: 'door-1', x: 11.8, z: -8, solvable: true },   // 4호실
  { id: 'note-1', x: 10, z: 8, solvable: false },     // 2호실
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
let botPrev = null; // 이동량 누적용 직전 위치
let botPathLen = 0; // 총 이동 거리(m). 벽에 박혀 멈추면 0에 가깝다
let lastBot = null; // 마지막 관측 위치(봇이 멈춘 "이유"를 가리기 위함)
let lastHuman = null;
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
  if (human) {
    me = { x: human.x, z: human.z };
    lastHuman = me;
  }

  if (human && dist(human.x, human.z, PARK.x, PARK.z) <= ARRIVE_R) parked = true;

  const bot = states.find((s) => s.id === BOT);
  if (!bot) return;
  botSeen += 1;

  // 이동량 누적 — 벽에 박혀 정지하는 회귀(2026-07-18)를 잡는 핵심 지표.
  if (botPrev) {
    botPathLen += Math.hypot(bot.x - botPrev.x, bot.z - botPrev.z);
  }
  botPrev = { x: bot.x, z: bot.z };
  lastBot = botPrev;

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
  console.log(`로스터: ${roster ? roster.map((r) => `${r.id}(${r.nick})${r.bot ? ' [bot]' : ''}`).join(', ') : '못 받음 ⚠️'}`);
  // 봇이 bot 플래그 없이 로스터에 실리면 프론트가 봇을 방장으로 뽑아 사람에게 시작 버튼이 사라진다.
  const botEntry = roster?.find((r) => r.id === BOT);
  if (botEntry && botEntry.bot !== true) {
    console.log('⚠️ 로스터의 봇에 bot=true가 없다 — 프론트 방장 선출이 봇을 사람으로 오인한다.');
  }
  console.log('\n봇의 최소 접근 거리:');
  for (const p of POIS) {
    console.log(`  ${p.id.padEnd(10)} ${f(min[p.id])}   ${p.solvable ? '(스크립트도 감)' : '★ LLM만 감'}`);
  }
  console.log(`  ${'사람'.padEnd(9)} ${f(min.human)}   (PARK ${PARK.x},${PARK.z} 정차 후 ${parkedSnapshots}스냅샷 기준)`);
  console.log(`\n봇 궤적: ${botPath.join(' → ') || '없음'}`);
  console.log(`봇 총 이동거리: ${botPathLen.toFixed(1)}m`);

  // 감옥 맵에선 이 스크립트의 사람도 길찾기가 없어 PARK까지 못 간다(감방에 갇힌다).
  // 그래서 정차 여부와 무관하게 마지막 위치를 찍는다 — 봇이 멈춘 이유가 "벽에 박혀서"인지
  // "사람 곁에 도착해서(FOLLOW 성공)"인지 이걸로 구분한다.
  const endGap = lastBot && lastHuman ? dist(lastBot.x, lastBot.z, lastHuman.x, lastHuman.z) : null;
  console.log(`\n마지막 위치 — 봇 (${lastBot?.x.toFixed(1)}, ${lastBot?.z.toFixed(1)})`
    + ` · 사람 (${lastHuman?.x.toFixed(1)}, ${lastHuman?.z.toFixed(1)})`);
  console.log(`봇↔사람 최종 거리: ${endGap === null ? '—' : endGap.toFixed(2) + 'm'}`
    + '  (ARRIVE_R 1.5m 이내면 FOLLOW 도착으로 선 것)');

  const llmAlive = min['note-1'] <= 1.5;
  // PARK 정차 기준만 쓰면 감옥 맵에선 영영 false다(이 스크립트의 사람은 감방을 못 벗어난다).
  // 사람이 어디에 있든 봇이 그 곁에 붙어 끝났으면 FOLLOW 성공으로 본다.
  const follow = (parked && min.human <= 2.0) || (endGap !== null && endGap <= 2.0);
  // 30초 동안 6m도 못 움직였으면 사실상 정지다(속도 6m/s이니 1초치 거리도 안 된다).
  const moving = botPathLen >= 6;

  console.log('\n──────── 판정 ────────');
  console.log(moving
    ? `✅ 봇이 움직인다 (${botPathLen.toFixed(1)}m)`
    : `❌ 봇이 사실상 정지 (${botPathLen.toFixed(1)}m) — 벽에 박혔거나 목표 좌표가 도달 불가.`
      + '\n   서버 Interactables.java의 POI 좌표가 프론트 interactables.ts와 일치하는지 볼 것.');

  if (botSeen === 0) {
    console.log('✗ 봇이 스냅샷에 없다. spawnBot/isEmpty를 볼 것.');
  } else if (llmAlive) {
    console.log('✅ LLM 느린 층 동작 — 봇이 note-1에 도달했다(스크립트 층은 쪽지에 가지 않는다).');
  } else {
    console.log('❌ LLM 층 미확인 — note-1 근처에 못 갔다. 봇은 스크립트로만 돈 듯하다.');
    console.log('   서버 로그의 "봇 계획:" / "봇 계획 실패(...)" 라인을 볼 것.');
  }
  console.log(follow ? '✅ FOLLOW_PLAYER 동작 — 사람 곁에 도착해 섰다(정지 이유가 벽이 아니다).'
                     : '· FOLLOW_PLAYER 미관측(이번 판단에 안 나왔을 뿐일 수 있다).');
  ws.close();
  // 감방문이 닫힌 채로는 note-1 도달이 불가능하므로 "움직이는가"를 종료 코드 기준으로 삼는다.
  process.exit(moving ? 0 : 1);
}
