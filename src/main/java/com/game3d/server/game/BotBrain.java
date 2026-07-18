package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 봇의 2계층 브레인.
 *
 * <ul>
 *   <li><b>빠른 층</b>({@link #steer}) — 매 tick. 현재 goal까지의 경로를 {@link BotNav}로 풀어
 *       다음 웨이포인트로 향하는 단위벡터를 낸다. 실제 이동·충돌은 Room.tick이 사람과 똑같이 처리한다.
 *       <br>⚠️ 예전엔 여기 "충돌 처리가 알아서 하니 벽 회피는 공짜"라고 적혀 있었는데 틀린 말이었다.
 *       충돌은 벽을 <b>통과하지 않게</b> 할 뿐 <b>돌아가게</b> 하지 않는다. 개활지 맵에선 티가 안 났지만
 *       감옥 맵에서 봇이 벽에 붙어 멈추는 회귀로 드러났다(2026-07-18).</li>
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

    // 길찾기 결과 캐시(루프 스레드 전용). BotNav는 시야 판정에 충돌 검사를 여러 번 돌리므로
    // 매 tick(20Hz) 돌리면 1 vCPU 서버엔 부담이다. 아래 조건에서만 다시 푼다.
    private double navX;
    private double navZ;
    private long navAtMs;
    private double navForX;
    private double navForZ;

    /** 경로 재탐색 주기(ms). 이보다 자주는 안 푼다. */
    private static final long NAV_REFRESH_MS = 400;
    /** 최종 목표가 이만큼 움직이면 즉시 재탐색(사람을 따라갈 때). */
    private static final double NAV_TARGET_MOVED = 1.5;
    /** 웨이포인트에 이만큼 다가가면 즉시 재탐색(다음 구간으로 넘어가려고). */
    private static final double NAV_REACHED = 1.0;

    /**
     * 이미 다녀온 POI(도착 순). 봇의 "기억"이다.
     *
     * 직전 한 곳만 기억하면, 해결 가능한 지점이 둘일 때 직전을 뺀 나머지가 항상 반대쪽 하나라
     * 둘 사이를 영원히 왕복한다. 또 이 기록을 프롬프트에 주지 않으면 모델은 매 호출을 첫 판단으로
     * 여겨 같은 쪽지를 계속 다시 고른다(2026-07 실측: LLM 계획 5/5가 같은 쪽지).
     *
     * 루프 스레드만 쓴다(steer). 플래너로 넘길 땐 불변 사본을 만든다.
     */
    private final Set<String> visited = new LinkedHashSet<>();

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
            visited.add(g.targetId()); // 다녀온 곳으로 기억 → 다시 고르지 않는다
            hasTarget = false; // 도착 → 다음 목표를 고른다
        }
        if (!hasTarget) {
            reconsider(self, players, solved);
            hasTarget = resolveTarget(goal, players, solved);
        }

        maybePlan(self, players, solved, nowMs);

        if (!hasTarget) {
            return STOP; // 갈 곳 없음(안 풀린 퍼즐이 없거나 방금 도착한 그곳뿐)
        }

        // 최종 목표가 아니라 "지금 향할 지점"으로 간다. 벽 너머면 경로상 다음 웨이포인트가 나온다.
        updateNav(self, nowMs);
        double dx = navX - self.x;
        double dz = navZ - self.z;
        double len = Math.hypot(dx, dz);
        return len < 1e-6 ? STOP : new double[] {dx / len, dz / len};
    }

    /** 길찾기 캐시 갱신. 주기가 지났거나, 목표가 크게 움직였거나, 웨이포인트에 다다랐을 때만 다시 푼다. */
    private void updateNav(Player self, long nowMs) {
        boolean stale = nowMs - navAtMs >= NAV_REFRESH_MS
                || Math.hypot(targetX - navForX, targetZ - navForZ) > NAV_TARGET_MOVED
                || Math.hypot(navX - self.x, navZ - self.z) < NAV_REACHED;
        if (!stale) {
            return;
        }
        double[] p = BotNav.steerPoint(self.x, self.z, targetX, targetZ);
        navX = p[0];
        navZ = p[1];
        navForX = targetX;
        navForZ = targetZ;
        navAtMs = nowMs;
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
     * 스크립트 느린 층: 아직 안 가본 안 풀린 퍼즐 중 최근접.
     * LLM이 꺼져 있거나 아직 응답이 없을 때 봇을 계속 움직이게 하는 바닥이다.
     *
     * 다 가봤으면 사람을 따라간다. 봇은 퍼즐을 못 푸니, 안 가본 곳이 없다는 건 사람이 뭔가 풀기
     * 전까지 새로 할 일이 없다는 뜻이다. 여기서 멈춰 세우면 봇이 벽처럼 서 있게 된다.
     * 사람이 퍼즐을 풀면 solved가 바뀌고, 그때 아래 nearestUnsolved가 다시 후보를 낸다.
     */
    private void reconsider(Player self, Collection<Player> players, Set<String> solved) {
        Interactables.Poi next = Interactables.nearestUnsolved(self.x, self.z, solved, visited);
        if (next != null) {
            goal = Goal.gotoPuzzle(next.id());
            return;
        }
        Player mate = nearestHuman(self, players);
        goal = mate == null ? Goal.IDLE : Goal.followPlayer(mate.id);
    }

    /** 같은 방의 사람 중 최근접. 아무도 없으면 null(봇만 남은 방). */
    private static Player nearestHuman(Player self, Collection<Player> players) {
        Player best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Player p : players) {
            if (p.bot) {
                continue;
            }
            double dx = p.x - self.x;
            double dz = p.z - self.z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
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
        return new BotContext(self.x, self.z, pois, mates, List.copyOf(visited));
    }

    /**
     * 모델이 낸 목표가 실재하는지 검증. 스냅샷 기준이라 살짝 낡을 수 있지만 스레드 안전하다.
     *
     * 이미 읽은 쪽지를 다시 고르는 건 무효로 본다. 프롬프트에 방문 기록을 넣어도 모델이 무시하고
     * 같은 쪽지를 계속 내놓기 때문에(2026-07 실측), 말로 부탁하는 대신 여기서 잘라낸다.
     * 무효 계획은 조용히 버려지고 스크립트 목표가 그대로 산다 — 우아한 열화가 여기서도 유지된다.
     */
    private static boolean valid(Goal g, BotContext ctx) {
        if (g.targetId() == null) {
            return g.action() == Goal.Action.IDLE;
        }
        Interactables.Poi p = Interactables.find(g.targetId());
        return switch (g.action()) {
            case IDLE -> true;
            case GOTO_PUZZLE -> p != null && p.solvable()
                    && ctx.pois().stream().anyMatch(v -> v.id().equals(p.id()) && !v.solved());
            case GOTO_NOTE -> p != null && !p.solvable() && !ctx.visitedIds().contains(p.id());
            case FOLLOW_PLAYER -> ctx.mates().stream().anyMatch(m -> m.id().equals(g.targetId()));
        };
    }

    private double distanceFrom(Player self) {
        return Math.hypot(targetX - self.x, targetZ - self.z);
    }
}
