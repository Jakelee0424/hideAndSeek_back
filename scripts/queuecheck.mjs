/**
 * 접속 대기열 검증. REST로 줄을 세우고 STOMP로 실제 입장까지 확인한다.
 *
 *   서버를 정원 2로 띄운 뒤:
 *   ./gradlew bootRun --args="--server.port=8081 --game.queue.capacity=2"
 *   node --experimental-websocket scripts/queuecheck.mjs [포트]
 *
 * ⚠️ botcheck/movecheck와 같은 이유로 --experimental-websocket이 필수다(Node 20).
 *
 * 확인하는 것
 *  1. 정원(2)까지는 ADMITTED, 그 뒤는 WAITING + 순번 1,2…
 *  2. 토큰을 들고 STOMP join → 실제로 방에 들어간다(스냅샷에 내가 보인다)
 *  3. 줄 서 있는 사람이 토큰 없이 직접 join → 거부된다(게이트가 장식이 아님)
 *  4. 입장자가 연결을 끊으면 → 대기열 맨 앞이 승급된다
 */

const PORT = process.argv[2] ?? '8080';
const BASE = `http://localhost:${PORT}`;
const ROOM = 'queuecheck';
const CAPACITY = 2; // 서버를 이 값으로 띄웠다고 가정

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? `  — ${detail}` : ''}`);
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── REST ─────────────────────────────────────────────────────────────────────

const enter = (id, nick) =>
  fetch(`${BASE}/api/queue`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, nick }),
  }).then((r) => r.json());

const status = (id) =>
  fetch(`${BASE}/api/queue/${id}`).then((r) => (r.status === 404 ? null : r.json()));

// ── raw STOMP (botcheck.mjs와 동일한 배관) ───────────────────────────────────

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

/**
 * STOMP로 접속해 join을 보내고, 스냅샷에 내가 등장하는지 본다.
 * @returns {Promise<{seen: boolean, close: () => void}>}
 */
function connectAndJoin(id, nick, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:${PORT}/ws`);
    let buf = '';
    let seen = false;
    const timer = setTimeout(() => resolve({ seen, close: () => ws.close() }), 1500);

    ws.addEventListener('error', () => {
      clearTimeout(timer);
      reject(new Error('WS 접속 실패'));
    });
    ws.addEventListener('open', () => {
      ws.send(frame('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0' }));
    });
    ws.addEventListener('message', (ev) => {
      buf += typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8');
      for (const f of parseFrames(buf)) {
        buf = f.rest;
        if (f.command === 'CONNECTED') {
          ws.send(frame('SUBSCRIBE', { id: `sub-${id}`, destination: `/topic/rooms/${ROOM}/state` }));
          ws.send(frame('SEND', { destination: `/app/rooms/${ROOM}/join`, 'content-type': 'application/json' },
            JSON.stringify({ id, nick, token })));
        } else if (f.command === 'MESSAGE') {
          const snap = JSON.parse(f.body);
          if ((snap.states ?? []).some((s) => s.id === id)) seen = true;
        }
      }
    });
  });
}

// ── 시나리오 ──────────────────────────────────────────────────────────────────

const run = async () => {
  console.log(`● 대기열 검증 (정원 ${CAPACITY} 가정)\n`);

  // 1. 정원까지 입장, 그 뒤는 대기
  const tickets = [];
  for (let i = 1; i <= 4; i++) {
    tickets.push(await enter(`p${i}`, `플레이어${i}`));
  }
  check('정원 안쪽은 ADMITTED',
    tickets[0].status === 'ADMITTED' && tickets[1].status === 'ADMITTED',
    `p1=${tickets[0].status}, p2=${tickets[1].status}`);
  check('정원 초과는 WAITING',
    tickets[2].status === 'WAITING' && tickets[3].status === 'WAITING',
    `p3=${tickets[2].status}, p4=${tickets[3].status}`);
  check('순번이 FIFO로 매겨짐',
    tickets[2].position === 1 && tickets[3].position === 2,
    `p3=${tickets[2].position}, p4=${tickets[3].position}`);
  check('토큰은 입장자에게만 발급',
    !!tickets[0].token && !!tickets[1].token && !tickets[2].token && !tickets[3].token);

  // 2. 재진입 멱등성 — 새로고침해도 순번이 밀리면 안 된다
  const again = await enter('p4', '플레이어4');
  check('재진입해도 순번 유지(멱등)', again.position === 2, `position=${again.position}`);

  // 3. 토큰 들고 실제 입장
  const c1 = await connectAndJoin('p1', '플레이어1', tickets[0].token);
  check('토큰 보유자는 실제로 입장됨', c1.seen, c1.seen ? '스냅샷에 등장' : '스냅샷에 없음');

  // 4. 줄 서 있는 사람이 토큰 없이 직접 접속 → 거부돼야 한다
  const c3 = await connectAndJoin('p3', '플레이어3', null);
  check('대기 중 무단 접속은 거부됨', !c3.seen,
    c3.seen ? '들어가졌다 — 게이트가 뚫렸다' : '스냅샷에 안 나타남');
  c3.close();

  // 5. 입장자가 나가면 맨 앞이 승급
  c1.close();
  await sleep(1200); // WS 종료 이벤트 + 폴링 간격
  const p3 = await status('p3');
  check('이탈 시 대기 1순위가 승급', p3?.status === 'ADMITTED',
    `p3=${p3?.status ?? '없음'}${p3?.token ? ' (토큰 발급됨)' : ''}`);

  const failed = results.filter((r) => !r.ok);
  console.log(`\n──────── ${results.length - failed.length}/${results.length} 통과 ────────`);
  process.exit(failed.length === 0 ? 0 : 1);
};

run().catch((e) => {
  console.error('✗ 검증 중 오류:', e.message);
  process.exit(1);
});
