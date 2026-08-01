package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 봇 길찾기 — <b>벽 데이터에서 구운 균일 격자</b>. 2026-08-01에 손으로 관리하던 웨이포인트
 * 그래프(옛 BotNav, 노드 18개 + 간선 목록)를 걷어내고 이걸로 바꿨다.
 *
 * <h2>왜 바꿨나</h2>
 * 웨이포인트는 맵이 바뀔 때마다 손으로 따라가야 했고, <b>틀려도 조용히 실패</b>했다. 실제 사고:
 * <ul>
 *   <li>화장실 노드를 넣고 EDGE_PAIRS를 빠뜨려 노드가 고립됨(인접은 자동 계산이 아니었다)</li>
 *   <li>escape-pipe의 최근접 노드가 하필 <b>잠긴 세탁실 안</b>이라 봇이 문 앞에서 110초 정지</li>
 *   <li>팀원의 맵 개편(2층·별관·조리실 분리)마다 노드를 손보게 됨</li>
 * </ul>
 * 격자는 {@link Collision}의 벽·소품에서 자동으로 나온다 — 맵이 바뀌어도 손댈 게 없고,
 * 부팅 때 {@link #reachabilityReport}로 <b>도달 불가를 전수 검사</b>할 수 있다.
 *
 * <h2>구조</h2>
 * 칸 0.5m, <b>레이어 둘</b>:
 * <ul>
 *   <li>레이어 0 — 1층. 칸 높이는 계단 사각형 안이면 램프 높이, 아니면 0.
 *       그래서 <b>계단이 저절로 이어진다</b>(램프 꼭대기 4.5 ↔ 2층 랜딩 4.5).</li>
 *   <li>레이어 1 — 수감동 2층 슬래브 위(높이 {@link Collision#FLOOR2_Y}). 슬래브가 없는 칸은 없다.</li>
 * </ul>
 * 이웃으로 갈 수 있는 조건은 <b>높이차 ≤ {@link Collision#STEP_UP}</b>이다(사람의 계단 스냅과 같은 규칙).
 * 그래서 감방 안(레이어0, 높이 0)과 그 머리 위 2층(레이어1, 높이 4.5)은 이어지지 않는다.
 *
 * <p>문은 격자에서 장애물로 보지 않는다(옛 웨이포인트와 같은 방침) — 봇은 문을 열고 지나가고,
 * 못 여는 문 뒤는 {@code Room.unreachableFor}가 목표에서 통째로 빼 준다.
 */
final class Nav {

    private static final Logger log = LoggerFactory.getLogger(Nav.class);

    /** 격자 칸 크기(m). 문 폭(2m 안팎)이 최소 3칸은 되게 잡았다 — 1m면 문이 격자에서 막힌다. */
    private static final double CELL = 0.5;

    private static final double X0 = -Collision.BOUND_X;
    private static final double Z0 = -Collision.BOUND_Z;
    private static final int COLS = (int) Math.ceil(2 * Collision.BOUND_X / CELL) + 1;
    private static final int ROWS = (int) Math.ceil(2 * Collision.BOUND_Z / CELL) + 1;
    private static final int LAYER = COLS * ROWS;
    private static final int SIZE = LAYER * 2;

    /** 칸을 딛고 설 수 있는가. 레이어1은 슬래브가 있는 칸만 true. */
    private static final boolean[] WALKABLE = new boolean[SIZE];
    /** 그 칸에 섰을 때 발바닥 높이. */
    private static final float[] STAND_Y = new float[SIZE];

    /** 8방향 이웃(대각선 포함). 대각선은 양옆이 둘 다 뚫려 있을 때만 쓴다(모서리 관통 방지). */
    private static final int[][] DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
    };

    static {
        bake();
    }

    private Nav() {}

    private static double cx(int c) {
        return X0 + c * CELL;
    }

    private static double cz(int r) {
        return Z0 + r * CELL;
    }

    private static int col(double x) {
        return clampInt((int) Math.round((x - X0) / CELL), 0, COLS - 1);
    }

    private static int row(double z) {
        return clampInt((int) Math.round((z - Z0) / CELL), 0, ROWS - 1);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int idx(int layer, int c, int r) {
        return layer * LAYER + r * COLS + c;
    }

    /** 발높이로 레이어를 고른다. 2층 바닥 근처면 1층이 아니다. */
    private static int layerOf(double feetY) {
        return feetY > Collision.FLOOR2_Y - Collision.STEP_UP ? 1 : 0;
    }

    /** 부팅 때 한 번. 칸마다 "설 수 있는가 / 서면 발높이가 얼마인가"를 채운다. */
    private static void bake() {
        long t0 = System.nanoTime();
        int walkable = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                double x = cx(c);
                double z = cz(r);

                double h0 = Collision.rampHeight(x, z); // 계단 밖이면 0
                boolean ok0 = !Collision.blockedByWall(x, z, h0);
                WALKABLE[idx(0, c, r)] = ok0;
                STAND_Y[idx(0, c, r)] = (float) h0;

                boolean ok1 = Collision.onSlab2(x, z)
                        && !Collision.blockedByWall(x, z, Collision.FLOOR2_Y);
                WALKABLE[idx(1, c, r)] = ok1;
                STAND_Y[idx(1, c, r)] = (float) Collision.FLOOR2_Y;

                walkable += (ok0 ? 1 : 0) + (ok1 ? 1 : 0);
            }
        }
        log.info("봇 길찾기 격자: {}x{}x2칸(={}m), 통행 가능 {}칸, 굽는 데 {}ms",
                COLS, ROWS, CELL, walkable, (System.nanoTime() - t0) / 1_000_000);
    }

    /** 두 칸이 서로 오갈 수 있는가(높이차가 걸어 오를 수 있는 턱 이내). */
    private static boolean linked(int from, int to) {
        return WALKABLE[to] && Math.abs(STAND_Y[to] - STAND_Y[from]) <= Collision.STEP_UP;
    }

    /** 그 (열,행)의 어느 레이어로든 갈 수 있는가. 대각선 모서리 컷 판정에 쓴다. */
    private static boolean linkedAny(int from, int c, int r) {
        return linked(from, idx(0, c, r)) || linked(from, idx(1, c, r));
    }

    /**
     * 막힌 칸에 서 있을 때(충돌 밀림 중이거나 소품에 낀 상태) 가장 가까운 통행 가능 칸.
     * 없으면 -1. 반경을 넓혀 가며 찾는다.
     */
    private static int nearestWalkable(int layer, int c, int r) {
        int here = idx(layer, c, r);
        if (WALKABLE[here]) {
            return here;
        }
        for (int rad = 1; rad <= 8; rad++) {
            for (int dr = -rad; dr <= rad; dr++) {
                for (int dc = -rad; dc <= rad; dc++) {
                    if (Math.max(Math.abs(dr), Math.abs(dc)) != rad) {
                        continue; // 테두리만
                    }
                    int nc = c + dc;
                    int nr = r + dr;
                    if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) {
                        continue;
                    }
                    int i = idx(layer, nc, nr);
                    if (WALKABLE[i]) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * 지금 이 tick에 향해야 할 지점 {x, z}. 목표가 같은 층에서 직선으로 보이면 목표 그대로,
     * 아니면 격자 경로를 풀어 <b>지금 위치에서 곧장 보이는 가장 먼 칸</b>으로 질러간다.
     *
     * <p>경로를 못 찾으면 목표를 그대로 돌려준다(길찾기 도입 전과 같은 동작 — 최소한 나빠지지 않는다).
     *
     * @param feetY 봇의 발바닥 높이, targetFeetY 목표의 발바닥 높이(POI는 0, 사람 추적은 그 사람 높이)
     */
    static double[] steerPoint(double x, double z, double feetY,
                               double tx, double tz, double targetFeetY) {
        int fromLayer = layerOf(feetY);
        int toLayer = layerOf(targetFeetY);
        if (fromLayer == toLayer && Collision.lineClear(x, z, tx, tz, feetY)) {
            return new double[] {tx, tz};
        }

        int start = nearestWalkable(fromLayer, col(x), row(z));
        int goal = nearestWalkable(toLayer, col(tx), row(tz));
        if (start < 0 || goal < 0) {
            return new double[] {tx, tz};
        }
        int[] path = bfs(start, goal);
        if (path == null) {
            return new double[] {tx, tz};
        }

        // 스무딩: 지금 위치에서 곧장 보이는 가장 먼 칸으로 질러간다. ⚠️ **같은 레이어 구간까지만**
        // 본다 — 계단을 건너뛰고 2층 칸으로 직선을 그으면 봇이 벽을 향해 걷는다.
        int limit = 0;
        while (limit + 1 < path.length && path[limit + 1] / LAYER == fromLayer) {
            limit++;
        }
        for (int i = limit; i >= 0; i--) {
            int cell = path[i] % LAYER;
            double px = cx(cell % COLS);
            double pz = cz(cell / COLS);
            if (Collision.lineClear(x, z, px, pz, feetY)) {
                return new double[] {px, pz};
            }
        }
        int cell = path[0] % LAYER;
        return new double[] {cx(cell % COLS), cz(cell / COLS)};
    }

    /**
     * start→goal 최단 경로(칸 인덱스). 못 가면 null.
     *
     * 칸이 4만 개라 BFS로 충분하다(최악 탐색도 1ms 미만이고, 호출은 봇당 400ms에 한 번이다).
     * 더 줄여야 하면 옥타일 휴리스틱 A*로 바꾸면 되지만, 지금은 단순한 쪽이 낫다.
     */
    private static int[] bfs(int start, int goal) {
        if (start == goal) {
            return new int[] {goal};
        }
        int[] prev = new int[SIZE];
        java.util.Arrays.fill(prev, -1);
        boolean[] seen = new boolean[SIZE];
        int[] queue = new int[SIZE];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = true;

        while (head < tail) {
            int cur = queue[head++];
            int layer = cur / LAYER;
            int cell = cur % LAYER;
            int c = cell % COLS;
            int r = cell / COLS;

            for (int[] d : DIRS) {
                int nc = c + d[0];
                int nr = r + d[1];
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) {
                    continue;
                }
                // 대각선은 양옆이 둘 다 뚫려 있어야 한다 — 아니면 벽 모서리를 스치듯 통과한다.
                if (d[0] != 0 && d[1] != 0
                        && (!linkedAny(cur, c + d[0], r) || !linkedAny(cur, c, r + d[1]))) {
                    continue;
                }
                // ⚠️ 이웃 칸은 **두 레이어 다** 본다. 계단 램프 꼭대기와 2층 랜딩은 같은 칸이 아니라
                //    **칸 경계에서** 만난다(램프 마지막 칸 x=-35.6 h=4.22 ↔ 랜딩 칸 x=-36.1 h=4.5).
                //    같은 칸에서만 갈아타게 두면 2층이 통째로 고립된다(전수 검사로 잡았다: 2층 0/1966).
                for (int nl = 0; nl < 2; nl++) {
                    int next = idx(nl, nc, nr);
                    if (seen[next] || !linked(cur, next)) {
                        continue;
                    }
                    seen[next] = true;
                    prev[next] = cur;
                    if (next == goal) {
                        return trace(prev, start, goal);
                    }
                    queue[tail++] = next;
                }
            }

            // 제자리 레이어 갈아타기(같은 x,z에서 높이차가 턱 이내). 위 이웃 검사와 겹치지만
            // 슬래브 가장자리가 정확히 칸 위에 떨어지는 경우를 놓치지 않으려고 남겨 둔다.
            int other = idx(1 - layer, c, r);
            if (!seen[other] && linked(cur, other)) {
                seen[other] = true;
                prev[other] = cur;
                if (other == goal) {
                    return trace(prev, start, goal);
                }
                queue[tail++] = other;
            }
        }
        return null;
    }

    private static int[] trace(int[] prev, int start, int goal) {
        int n = 0;
        for (int at = goal; at != -1; at = prev[at]) {
            n++;
            if (at == start) {
                break;
            }
        }
        int[] path = new int[n];
        int at = goal;
        for (int i = n - 1; i >= 0; i--) {
            path[i] = at;
            at = prev[at];
        }
        return path;
    }

    // ── 검증 도구 ────────────────────────────────────────────────────────────
    // 웨이포인트로는 못 하던 것. 노드 고립·잠긴 방 매핑 같은 사고가 조용히 지나가지 못한다.

    /** (x,z,feetY)에서 (tx,tz,targetFeetY)로 격자 경로가 있는가. */
    static boolean reachable(double x, double z, double feetY, double tx, double tz, double targetFeetY) {
        int start = nearestWalkable(layerOf(feetY), col(x), row(z));
        int goal = nearestWalkable(layerOf(targetFeetY), col(tx), row(tz));
        return start >= 0 && goal >= 0 && (start == goal || bfs(start, goal) != null);
    }

    /**
     * 1층 복도에서 출발해 <b>격자 전체 중 얼마나 닿는지</b>. 특히 2층 칸이 몇 개나 닿는지가
     * 계단 연결이 살아 있다는 증거다 — 0이면 램프가 어딘가에서 끊긴 것이다.
     * (POI는 전부 1층이라 {@link #reachabilityReport}만으로는 2층을 검증할 수 없다.)
     */
    static String connectivityReport() {
        int start = nearestWalkable(0, col(0), row(17)); // 연결 복도 한가운데
        if (start < 0) {
            return "1층 복도에서 설 수 있는 칸을 못 찾았다";
        }
        boolean[] seen = new boolean[SIZE];
        int[] queue = new int[SIZE];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        seen[start] = true;
        int[] hit = new int[2];
        while (head < tail) {
            int cur = queue[head++];
            hit[cur / LAYER]++;
            int layer = cur / LAYER;
            int cell = cur % LAYER;
            int c = cell % COLS;
            int r = cell / COLS;
            for (int[] d : DIRS) {
                int nc = c + d[0];
                int nr = r + d[1];
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) {
                    continue;
                }
                // ⚠️ bfs()와 **같은 확장 규칙**이어야 한다(이웃 칸의 두 레이어를 다 본다).
                //    여기만 옛 규칙으로 두면 실제로는 이어져 있는데 "2층 0"이라고 보고한다.
                for (int nl = 0; nl < 2; nl++) {
                    int next = idx(nl, nc, nr);
                    if (!seen[next] && linked(cur, next)) {
                        seen[next] = true;
                        queue[tail++] = next;
                    }
                }
            }
            int other = idx(1 - layer, c, r);
            if (!seen[other] && linked(cur, other)) {
                seen[other] = true;
                queue[tail++] = other;
            }
        }
        int total2 = 0;
        for (int i = LAYER; i < SIZE; i++) {
            if (WALKABLE[i]) {
                total2++;
            }
        }
        return String.format("1층 복도에서 닿는 칸 — 1층 %d, 2층 %d/%d", hit[0], hit[1], total2);
    }

    /**
     * 모든 POI 쌍 사이에 경로가 있는지 전수 검사. 못 가는 쌍을 사람이 읽을 문장으로 돌려준다.
     * 빈 문자열이면 전부 통과. 부팅 로그와 테스트가 같이 쓴다.
     */
    static String reachabilityReport() {
        StringBuilder sb = new StringBuilder();
        var all = Interactables.all();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                var a = all.get(i);
                var b = all.get(j);
                if (!reachable(a.x(), a.z(), 0, b.x(), b.z(), 0)) {
                    sb.append(a.id()).append(" ↔ ").append(b.id()).append(" 경로 없음\n");
                }
            }
        }
        return sb.toString();
    }
}
