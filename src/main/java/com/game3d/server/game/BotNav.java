package com.game3d.server.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 봇 길찾기(웨이포인트 그래프).
 *
 * <h2>왜 필요한가</h2>
 * 빠른 층은 원래 "목표를 향한 단위벡터"로만 움직였다. 개활지 맵에선 직선이 곧 정답이었지만,
 * 감옥은 감방·복도·통로로 나뉜 구조라 직선 이동은 벽에 박히는 게 기본값이다.
 * 충돌 처리(Collision.resolve)는 벽을 <b>통과하지 않게</b> 해줄 뿐 <b>돌아가게</b> 해주지 않는다.
 * 실측(2026-07-18): 1호실에서 4호실 목표로 직진하다 x=0 벽에 붙어 30초 중 27초를 정지.
 *
 * <h2>왜 A* 격자가 아닌가</h2>
 * 맵이 손으로 만든 고정 구조라 통행 가능한 위상이 뻔하다(감방 4 → 중앙 복도 → 동/서 통로 →
 * 운동장/식당). 노드 17개면 충분하고, 격자 A*보다 훨씬 싸고 디버깅도 쉽다.
 * 맵이 바뀌면 아래 NODES/EDGES를 손으로 고칠 것 — prisonLayout.ts가 바뀌면 여기도 봐야 한다.
 *
 * <h2>닫힌 감방문</h2>
 * 문은 아예 장애물로 보지 않는다(시야 판정도 {@link Collision#blockedByWall}로 벽만 본다).
 * 봇은 문 근처(BOT_DOOR_RANGE)에 가면 스스로 열기 때문에(Room.tick), 닫힌 문을 지나는 경로도
 * 실제로 통행 가능하다. 반대로 문을 장애물로 세면 문 좌표 자체가 통행 불가가 되어, 그 문을
 * 웨이포인트로 삼는 시야 검사가 항상 실패한다 → 봇이 감방 안에서 제자리만 맴돈다.
 */
final class BotNav {

    private record Node(double x, double z) {}

    /** 통행 가능 지점. 인덱스가 EDGES의 참조 번호다. */
    private static final Node[] NODES = {
        new Node(-7, 6.5),   // 0  1호실 안
        new Node(-7, 2.5),   // 1  1호실 문
        new Node(7, 6.5),    // 2  2호실 안
        new Node(7, 2.5),    // 3  2호실 문
        new Node(-7, -6.5),  // 4  3호실 안
        new Node(-7, -2.5),  // 5  3호실 문
        new Node(7, -6.5),   // 6  4호실 안
        new Node(7, -2.5),   // 7  4호실 문
        new Node(-7, 0),     // 8  중앙 복도 서
        new Node(0, 0),      // 9  중앙 복도 중앙
        new Node(7, 0),      // 10 중앙 복도 동
        new Node(-13, 0),    // 11 복도 서쪽 끝(감방블록 출구)
        new Node(13, 0),     // 12 복도 동쪽 끝
        new Node(-20, 0),    // 13 서통로
        new Node(20, 0),     // 14 동통로
        new Node(-35, 0),    // 15 식당
        new Node(35, 0),     // 16 운동장
    };

    private static final int[][] EDGE_PAIRS = {
        {0, 1}, {1, 8},           // 1호실 → 문 → 복도
        {2, 3}, {3, 10},          // 2호실
        {4, 5}, {5, 8},           // 3호실
        {6, 7}, {7, 10},          // 4호실
        {8, 9}, {9, 10},          // 중앙 복도
        {8, 11}, {11, 13}, {13, 15}, // 서쪽: 복도 → 서통로 → 식당
        {10, 12}, {12, 14}, {14, 16}, // 동쪽: 복도 → 동통로 → 운동장
    };

    private static final int[][] ADJ = buildAdjacency();

    /** 시야 판정 표본 간격(m). 촘촘할수록 정확하지만 tick 비용이 는다. */
    private static final double LOS_STEP = 0.5;

    private BotNav() {}

    /**
     * 지금 이 tick에 실제로 향해야 할 지점. 목표가 직선으로 보이면 목표 그대로,
     * 아니면 경로상 다음 웨이포인트를 돌려준다. 경로를 못 찾으면 목표를 그대로 돌려준다
     * (길찾기 도입 전과 같은 동작 — 최소한 나빠지지는 않는다).
     */
    static double[] steerPoint(double x, double z, double tx, double tz) {
        if (lineClear(x, z, tx, tz)) {
            return new double[] {tx, tz};
        }
        int start = nearestNode(x, z);
        int goal = nearestNode(tx, tz);
        int[] path = bfs(start, goal);
        if (path == null) {
            return new double[] {tx, tz};
        }
        // 경로 스무딩: 지금 위치에서 곧장 보이는 가장 먼 노드로 질러간다.
        // 없으면 경로의 첫 노드로 간다(항상 보이도록 nearestNode가 시야를 우선한다).
        for (int i = path.length - 1; i >= 0; i--) {
            Node n = NODES[path[i]];
            if (lineClear(x, z, n.x(), n.z())) {
                return new double[] {n.x(), n.z()};
            }
        }
        Node n = NODES[path[0]];
        return new double[] {n.x(), n.z()};
    }

    /** 시야가 닿는 것 중 최근접 노드. 하나도 안 보이면(벽에 낀 경우) 그냥 최근접. */
    private static int nearestNode(double x, double z) {
        int visible = -1;
        double visibleD2 = Double.MAX_VALUE;
        int any = 0;
        double anyD2 = Double.MAX_VALUE;
        for (int i = 0; i < NODES.length; i++) {
            double dx = NODES[i].x() - x;
            double dz = NODES[i].z() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < anyD2) {
                anyD2 = d2;
                any = i;
            }
            if (d2 < visibleD2 && lineClear(x, z, NODES[i].x(), NODES[i].z())) {
                visibleD2 = d2;
                visible = i;
            }
        }
        return visible >= 0 ? visible : any;
    }

    /** start→goal 최단 경로(노드 인덱스). 못 가면 null. 노드 17개라 BFS로 충분하다. */
    private static int[] bfs(int start, int goal) {
        if (start == goal) {
            return new int[] {goal};
        }
        int[] prev = new int[NODES.length];
        boolean[] seen = new boolean[NODES.length];
        java.util.Arrays.fill(prev, -1);
        seen[start] = true;

        Deque<Integer> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (int next : ADJ[cur]) {
                if (seen[next]) {
                    continue;
                }
                seen[next] = true;
                prev[next] = cur;
                if (next == goal) {
                    return trace(prev, start, goal);
                }
                q.add(next);
            }
        }
        return null;
    }

    private static int[] trace(int[] prev, int start, int goal) {
        List<Integer> rev = new ArrayList<>();
        for (int at = goal; at != -1; at = prev[at]) {
            rev.add(at);
            if (at == start) {
                break;
            }
        }
        int[] path = new int[rev.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = rev.get(path.length - 1 - i); // 뒤집어서 start→goal 순으로
        }
        return path;
    }

    /** 두 점을 잇는 선이 통행 가능한가. 표본을 찍어 벽에 걸리는지 본다(문은 무시 — 위 설명 참고). */
    private static boolean lineClear(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        int steps = (int) Math.ceil(len / LOS_STEP);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            if (Collision.blockedByWall(x1 + dx * t, z1 + dz * t)) {
                return false;
            }
        }
        return true;
    }

    private static int[][] buildAdjacency() {
        List<List<Integer>> lists = new ArrayList<>(NODES.length);
        for (int i = 0; i < NODES.length; i++) {
            lists.add(new ArrayList<>());
        }
        for (int[] e : EDGE_PAIRS) {
            lists.get(e[0]).add(e[1]);
            lists.get(e[1]).add(e[0]);
        }
        int[][] adj = new int[NODES.length][];
        for (int i = 0; i < NODES.length; i++) {
            adj[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        return adj;
    }
}
