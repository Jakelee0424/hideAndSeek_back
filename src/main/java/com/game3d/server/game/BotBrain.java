package com.game3d.server.game;

import java.util.Set;

/**
 * AI 봇의 2계층 브레인.
 *
 * <ul>
 *   <li><b>빠른 층</b>({@link #steer}) — 매 tick. 현재 goal의 목표 지점으로 향하는 단위벡터만 계산한다.
 *       실제 이동·충돌은 Room.tick이 사람과 똑같이 처리하므로 벽 회피는 공짜로 따라온다.</li>
 *   <li><b>느린 층</b>({@link #reconsider}) — 목표가 낡았을 때만. 지금은 스크립트로 고른다.</li>
 * </ul>
 *
 * goal이 volatile인 이유: 2단계에서 느린 층이 Groq 호출로 바뀌면 별도 스레드에서 goal을 갱신하고
 * tick은 최신값만 읽게 된다. 호출이 늦거나 실패해도 빠른 층은 마지막 goal을 계속 실행한다(우아한 열화).
 */
final class BotBrain {

    /** 목표에 이 거리 안으로 들어오면 도착으로 본다(프론트 INTERACT_RANGE 2.2보다 안쪽). */
    private static final double ARRIVE_R = 1.5;

    /** 정지. 호출부는 읽기만 한다(핫패스 할당 회피용 공유 상수). */
    private static final double[] STOP = {0, 0};

    private volatile Goal goal = Goal.IDLE;

    /** 방금 도착한 목표. 같은 곳에 계속 붙어 있지 않도록 다음 선택에서 제외한다. */
    private String lastArrivedId;

    Goal goal() {
        return goal;
    }

    /** 빠른 층: 이번 tick의 이동 의도(단위벡터). 목표가 없거나 낡았으면 느린 층을 돌린다. */
    double[] steer(double x, double z, Set<String> solved) {
        Puzzles.Puzzle target = resolve(solved);

        if (target != null && distance(target, x, z) <= ARRIVE_R) {
            lastArrivedId = target.id();
            target = null; // 도착 → 다음 목표를 고른다
        }
        if (target == null) {
            reconsider(x, z, solved);
            target = resolve(solved);
        }
        if (target == null) {
            return STOP; // 갈 곳 없음(안 풀린 퍼즐이 없거나 방금 도착한 그곳뿐)
        }

        double dx = target.x() - x;
        double dz = target.z() - z;
        double len = Math.hypot(dx, dz);
        return len < 1e-6 ? STOP : new double[] {dx / len, dz / len};
    }

    /** 현재 goal이 가리키는 유효한 목표. IDLE이거나 이미 해결된 퍼즐이면 null. */
    private Puzzles.Puzzle resolve(Set<String> solved) {
        Goal g = goal;
        if (g.action() != Goal.Action.GOTO_PUZZLE || solved.contains(g.targetId())) {
            return null;
        }
        return Puzzles.find(g.targetId());
    }

    /**
     * 느린 층: 무엇을 할지 결정한다. 지금은 스크립트 — 방금 도착한 곳을 뺀 안 풀린 퍼즐 중 최근접.
     * 후보가 둘 이상이면 자연스럽게 퍼즐 사이를 오간다.
     *
     * 2단계에서 이 메서드 본문이 Groq 호출로 교체된다. 그때는 여기서 바로 정하지 않고
     * 스케줄러(5초+ 주기)가 비동기로 goal만 갈아끼우는 형태가 된다.
     */
    private void reconsider(double x, double z, Set<String> solved) {
        Puzzles.Puzzle next = Puzzles.nearestUnsolved(x, z, solved, lastArrivedId);
        goal = next == null ? Goal.IDLE : Goal.gotoPuzzle(next.id());
    }

    private static double distance(Puzzles.Puzzle p, double x, double z) {
        return Math.hypot(p.x() - x, p.z() - z);
    }
}
