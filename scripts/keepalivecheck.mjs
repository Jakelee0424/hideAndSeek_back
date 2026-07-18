/**
 * 대기열 keepalive 검증. 접속 관문(사이트 진입 즉시 대기열)에서 생기는 문제를 확인한다.
 *
 *   서버를 짧은 TTL로 띄운 뒤:
 *   ./gradlew bootRun --args="--server.port=8081 --game.queue.capacity=2 --game.queue.token-ttl=5s"
 *   node scripts/keepalivecheck.mjs [포트]
 *
 * 무엇을 확인하나
 *   통과했지만 아직 게임에 접속하지 않은 사람(로비에서 닉네임 치는 중)은 TTL이 지나도
 *   자리를 잃으면 안 된다. 대신 순번 폴링(GET)이 keepalive 역할을 한다.
 *   반대로 탭을 닫아 폴링이 끊긴 사람은 예정대로 회수되어야 한다 — 안 그러면 자리가 샌다.
 *
 *   k1: 계속 폴링   → TTL이 지나도 살아 있어야 한다
 *   k2: 폴링 안 함  → TTL 뒤 회수되어야 한다
 *
 * fetch만 쓰므로 --experimental-websocket이 필요 없다.
 */

const PORT = process.argv[2] ?? '8080';
const BASE = `http://localhost:${PORT}`;
const TTL_MS = 5000; // 서버를 이 값으로 띄웠다고 가정
const OBSERVE_MS = TTL_MS * 2.4; // TTL을 확실히 넘기는 관측 시간
const POLL_MS = 2000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const enter = (id) =>
  fetch(`${BASE}/api/queue`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, nick: id }),
  }).then((r) => r.json());

const status = (id) =>
  fetch(`${BASE}/api/queue/${id}`).then((r) => (r.status === 404 ? null : r.json()));

const run = async () => {
  // 앞선 검증이 남긴 슬롯이 있으면 정원이 막힌다 → 고유 id를 쓴다.
  const stamp = process.hrtime.bigint().toString().slice(-6);
  const k1 = `keep-${stamp}`;
  const k2 = `drop-${stamp}`;

  console.log(`● keepalive 검증 (TTL ${TTL_MS / 1000}s 가정, ${OBSERVE_MS / 1000}s 관측)\n`);

  const t1 = await enter(k1);
  const t2 = await enter(k2);
  if (t1.status !== 'ADMITTED' || t2.status !== 'ADMITTED') {
    console.error(`✗ 준비 실패 — 둘 다 ADMITTED여야 한다 (k1=${t1.status}, k2=${t2.status}).`);
    console.error('  이전 검증의 슬롯이 남아 있을 수 있다. 서버를 재시작하고 다시 돌릴 것.');
    process.exit(1);
  }
  console.log(`  둘 다 입장 허가됨. 이제 ${k1}만 ${POLL_MS / 1000}초마다 폴링한다...`);

  const deadline = Date.now() + OBSERVE_MS;
  while (Date.now() < deadline) {
    await sleep(POLL_MS);
    await status(k1); // k1만 keepalive. k2는 방치.
  }

  const after1 = await status(k1);
  const after2 = await status(k2);

  const ok1 = after1?.status === 'ADMITTED';
  const ok2 = after2 === null;

  console.log();
  console.log(`${ok1 ? '✅' : '❌'} 폴링 중인 슬롯은 TTL이 지나도 유지  — ${k1}=${after1?.status ?? '회수됨'}`);
  console.log(`${ok2 ? '✅' : '❌'} 방치된 슬롯은 TTL 뒤 회수         — ${k2}=${after2?.status ?? '회수됨'}`);

  console.log(`\n──────── ${[ok1, ok2].filter(Boolean).length}/2 통과 ────────`);
  process.exit(ok1 && ok2 ? 0 : 1);
};

run().catch((e) => {
  console.error('✗ 검증 중 오류:', e.message);
  process.exit(1);
});
