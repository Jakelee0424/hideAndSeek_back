package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 봇의 2계층 브레인.
 *
 * <ul>
 *   <li><b>빠른 층</b>({@link #steer}) — 매 tick. 현재 goal의 목표 지점으로 향하는 단위벡터만 계산한다.
 *       실제 이동·충돌은 Room.tick이 사람과 똑같이 처리하므로 벽 회피는 공짜로 따라온다.</li>
 *   <li><b>느린 층</b> — 두 겹이다. 스크립트({@link #reconsider})가 항상 즉시 목표를 채우고,
 *       LLM({@link BotPlanner})이 준비되면 그 위에 덮어쓴다.</li>
 * </ul>
 *
 * LLM 호출은 가상 스레드에서 돌고 tick 스레드는 절대 기다리지 않는다. 호출이 늦거나 실패하면
 * 빠른 층은 마지막 goal(최소한 스크립트 목표)을 계속 실행한다 — 봇이 멈추는 경우는 없다.
 * goal이 volatile인 이유가 이것이다: 플래너 스레드가 쓰고 tick은 최신값만 읽는다.
 */
final class BotBrain {

    private static final Logger log = LoggerFactory.getLogger(BotBrain.class);

    /** 목표에 이 거리 안으로 들어오면 도착으로 본다(프론트 INTERACT_RANGE 2.2보다 안쪽). */
    private static final double ARRIVE_R = 1.5;

    /** 정지. 호출부는 읽기만 한다(핫패스 할당 회피용 공유 상수). */
    private static final double[] STOP = {0, 0};

    /** null이면 스크립트로만 돈다(LLM 비활성/키 없음). */
    private final BotPlanner llm;
    private final long intervalMs;

    /** 호출 중복 방지. 응답이 주기보다 느려도 요청이 쌓이지 않는다. */
    private final AtomicBoolean inFlight = new AtomicBoolean();

    private volatile Goal goal = Goal.IDLE;
    private volatile long lastPlanAtMs;

    // 목표 좌표: 루프 스레드만 쓴다(tick마다 배열 새로 만들지 않으려고 필드로 둔다).
    private double targetX;
    private double targetZ;

    /** 방금 도착한 목표. 같은 곳에 계속 붙어 있지 않도록 다음 선택에서 제외한다. */
    private String lastArrivedId;

    BotBrain(BotPlanner llm, long intervalMs) {
        this.llm = llm;
        this.intervalMs = intervalMs;
    }

    Goal goal() {
        return goal;
    }

    /** 빠른 층: 이번 tick의 이동 의도(단위벡터). 목표가 없거나 낡았으면 스크립트로 즉시 다시 고른다. */
    double[] steer(Player self, Collection<Player> players, Set<String> solved, long nowMs) {
        Goal g = goal;
        boolean hasTarget = resolveTarget(g, players, solved);

        if (hasTarget && distanceFrom(self) <= ARRIVE_R) {
            if (g.action() == Goal.Action.FOLLOW_PLAYER) {
                // 따라가기는 도착해도 끝나지 않는다. 곁에 서서 다음 계획을 기다린다.
                maybePlan(self, players, solved, nowMs);
                return STOP;
            }
            lastArrivedId = g.targetId();
            hasTarget = false; // 도착 → 다음 목표를 고른다
        }
        if (!hasTarget) {
            reconsider(self, solved);
            hasTarget = resolveTarget(goal, players, solved);
        }

        maybePlan(self, players, solved, nowMs);

        if (!hasTarget) {
            return STOP; // 갈 곳 없음(안 풀린 퍼즐이 없거나 방금 도착한 그곳뿐)
        }

        double dx = targetX - self.x;
        double dz = targetZ - self.z;
        double len = Math.hypot(dx, dz);
        return len < 1e-6 ? STOP : new double[] {dx / len, dz / len};
    }

    /**
     * goal이 가리키는 지점의 좌표를 targetX/targetZ에 채운다.
     *
     * @return 유효한 목표가 있으면 true. IDLE·해결된 퍼즐·나간 플레이어면 false.
     */
    private boolean resolveTarget(Goal g, Collection<Player> players, Set<String> solved) {
        switch (g.action()) {
            case GOTO_PUZZLE, GOTO_NOTE -> {
                Interactables.Poi p = Interactables.find(g.targetId());
                if (p == null || (p.solvable() && solved.contains(p.id()))) {
                    return false;
                }
                targetX = p.x();
                targetZ = p.z();
                return true;
            }
            case FOLLOW_PLAYER -> {
                for (Player p : players) {
                    if (!p.bot && p.id.equals(g.targetId())) {
                        targetX = p.x;
                        targetZ = p.z;
                        return true;
                    }
                }
                return false; // 따라가던 사람이 나갔다
            }
            case IDLE -> {
                return false;
            }
        }
        return false;
    }

    /**
     * 스크립트 느린 층: 방금 도착한 곳을 뺀 안 풀린 퍼즐 중 최근접.
     * LLM이 꺼져 있거나 아직 응답이 없을 때 봇을 계속 움직이게 하는 바닥이다.
     */
    private void reconsider(Player self, Set<String> solved) {
        Interactables.Poi next = Interactables.nearestUnsolved(self.x, self.z, solved, lastArrivedId);
        goal = next == null ? Goal.IDLE : Goal.gotoPuzzle(next.id());
    }

    /** LLM 느린 층: 주기가 됐고 앞선 호출이 끝났을 때만. tick 스레드는 여기서 절대 기다리지 않는다. */
    private void maybePlan(Player self, Collection<Player> players, Set<String> solved, long nowMs) {
        if (llm == null || nowMs - lastPlanAtMs < intervalMs || !inFlight.compareAndSet(false, true)) {
            return;
        }
        lastPlanAtMs = nowMs;
        BotContext ctx = snapshot(self, players, solved);

        Thread.startVirtualThread(() -> {
            try {
                Goal planned = llm.plan(ctx);
                if (planned != null && valid(planned, ctx)) {
                    goal = planned;
                } else if (planned != null) {
                    // 모델이 없는 id를 지어냈다. 무시하면 스크립트 목표가 그대로 산다.
                    log.warn("봇 계획 무효, 무시함: {} {}", planned.action(), planned.targetId());
                }
            } catch (Exception e) {
                log.warn("봇 계획 실패({}), 스크립트로 계속: {}", e.getClass().getSimpleName(), e.getMessage());
            } finally {
                inFlight.set(false);
            }
        });
    }

    /** 계획 요청 시점의 월드 사본. 수 초에 한 번만 만든다(핫패스 아님). */
    private BotContext snapshot(Player self, Collection<Player> players, Set<String> solved) {
        List<BotContext.PoiView> pois = new ArrayList<>(Interactables.all().size());
        for (Interactables.Poi p : Interactables.all()) {
            pois.add(new BotContext.PoiView(p.id(), p.x(), p.z(), p.label(), solved.contains(p.id())));
        }
        List<BotContext.MateView> mates = new ArrayList<>();
        for (Player p : players) {
            if (!p.bot) {
                mates.add(new BotContext.MateView(p.id, p.nick, p.x, p.z));
            }
        }
        return new BotContext(self.x, self.z, pois, mates, lastArrivedId);
    }

    /** 모델이 낸 목표가 실재하는지 검증. 스냅샷 기준이라 살짝 낡을 수 있지만 스레드 안전하다. */
    private static boolean valid(Goal g, BotContext ctx) {
        if (g.targetId() == null) {
            return g.action() == Goal.Action.IDLE;
        }
        Interactables.Poi p = Interactables.find(g.targetId());
        return switch (g.action()) {
            case IDLE -> true;
            case GOTO_PUZZLE -> p != null && p.solvable()
                    && ctx.pois().stream().anyMatch(v -> v.id().equals(p.id()) && !v.solved());
            case GOTO_NOTE -> p != null && !p.solvable();
            case FOLLOW_PLAYER -> ctx.mates().stream().anyMatch(m -> m.id().equals(g.targetId()));
        };
    }

    private double distanceFrom(Player self) {
        return Math.hypot(targetX - self.x, targetZ - self.z);
    }
}
