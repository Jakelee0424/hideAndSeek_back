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

    /**
     * 자물쇠 앞에 서서 "푸는 척" 머무는 시간(ms).
     *
     * 봇은 퍼즐 UI가 없어 사실 즉시 풀 수 있지만, 도착하자마자 문이 열리면 보는 사람에게
     * 대놓고 치트로 보인다. 잠깐 멈춰 있다가 열리면 사람이 푸는 모습과 구분되지 않는다.
     */
    private static final long SOLVE_DWELL_MS = 5000;

    /**
     * 쪽지 앞에서 읽는 시늉으로 멈춰 있는 시간(ms).
     *
     * 없으면 도착하자마자 다음 쪽지로 튀어서, 봇이 감방 사이를 쉼 없이 왕복하는 것처럼 보인다.
     */
    private static final long READ_DWELL_MS = 2500;

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

    /** 이번 tick에 못 닿는 POI. 루프 스레드가 steer 진입 때 채우고 그 안에서만 읽는다. */
    private Set<String> blocked = Set.of();

    // "푸는 중"인 자물쇠와 그 완료 시각. 루프 스레드 전용.
    private String solvingId;
    private long solvingUntilMs;

    // 쪽지를 읽느라 멈춰 있는 시각까지와, 이번 목표에서 이미 읽기를 마쳤는지. 루프 스레드 전용.
    // readDoneId가 없으면 읽기가 끝나자마자 같은 쪽지에서 또 읽기가 걸려 영영 못 떠난다.
    private long readingUntilMs;
    private String readDoneId;

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

    /**
     * 머무는 시간을 다 채운 자물쇠 id를 <b>한 번만</b> 돌려준다. 아직이거나 없으면 null.
     * Room이 tick마다 수거해 solvedIds에 넣고 그 방 문을 연다.
     *
     * @param notBeforeMs 이 시각 전에는 해제하지 않는다. Room이 "첫 사람이 나간 뒤 N초"를
     *                    여기로 넘긴다. 아직 아무도 안 나왔으면 Long.MAX_VALUE라 계속 기다린다.
     *                    조건이 안 맞으면 상태를 지우지 않는다 — 다음 tick에 다시 판정해야 한다.
     */
    String pollSolved(long nowMs, long notBeforeMs) {
        if (solvingId == null || nowMs < solvingUntilMs || nowMs < notBeforeMs) {
            return null;
        }
        String done = solvingId;
        solvingId = null;
        return done;
    }

    /**
     * 빠른 층: 이번 tick의 이동 의도(단위벡터). 목표가 없거나 낡았으면 스크립트로 즉시 다시 고른다.
     *
     * blocked는 지금 물리적으로 못 닿는 POI(잠긴 남의 감방 안 자물쇠 등)다. 후보에서 빼지 않으면
     * 봇이 열 수 없는 문 앞에 붙어 멈춘다. 판정은 Room이 한다(잠금 규칙을 아는 쪽이 거기라서).
     */
    double[] steer(Player self, Collection<Player> players, Set<String> solved,
                   Set<String> blocked, long nowMs) {
        this.blocked = blocked;

        // 자물쇠를 "푸는 중"이면 그 자리에 서 있는다. 시간이 차면 Room이 pollSolved로 수거한다.
        if (solvingId != null) {
            return STOP;
        }

        // 쪽지를 읽는 중이면 그 앞에 멈춰 선다.
        if (nowMs < readingUntilMs) {
            return STOP;
        }

        Goal g = goal;
        boolean hasTarget = resolveTarget(g, players, solved);

        if (hasTarget && distanceFrom(self) <= ARRIVE_R) {
            if (g.action() == Goal.Action.FOLLOW_PLAYER) {
                // 따라가기는 도착해도 끝나지 않는다. 곁에 서서 다음 계획을 기다린다.
                maybePlan(self, players, solved, nowMs);
                return STOP;
            }
            visited.add(g.targetId()); // 다녀온 곳으로 기억 → 다시 고르지 않는다

            // 봇이 풀 수 있는 자물쇠에 도착했으면 여기서부터 "푸는 척" 머문다.
            Interactables.Poi arrived = Interactables.find(g.targetId());
            if (arrived != null && arrived.botSolvable() && !solved.contains(arrived.id())) {
                solvingId = arrived.id();
                solvingUntilMs = nowMs + SOLVE_DWELL_MS;
                log.info("봇이 {} 앞에서 여는 중", arrived.id());
                return STOP;
            }

            // 쪽지면 잠깐 읽고 간다. readDoneId로 "이번 목표에선 이미 읽었다"를 표시하지 않으면
            // 읽기가 끝난 다음 tick에 같은 자리에서 또 읽기가 걸려 그 쪽지를 영영 못 떠난다.
            if (arrived != null && !arrived.solvable() && !arrived.id().equals(readDoneId)) {
                readingUntilMs = nowMs + READ_DWELL_MS;
                readDoneId = arrived.id();
                return STOP;
            }

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
                // 못 닿게 된 목표는 버린다 — 붙잡고 있으면 그쪽 벽으로 계속 밀고 간다.
                if (p == null || blocked.contains(g.targetId())
                        || (p.solvable() && solved.contains(p.id()))) {
                    return false;
                }
                targetX = p.x();
                targetZ = p.z();
                return true;
            }
            case FOLLOW_PLAYER -> {
                if (blocked.contains(g.targetId())) {
                    return false; // 따라가던 사람이 잠긴 감방 안으로 판정됐다
                }
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
        // 새 목표를 고르는 참이니 읽기 표시를 푼다. 안 그러면 나중에 같은 쪽지를 다시 골랐을 때
        // (순회 기록 초기화 뒤) 멈추지 않고 지나친다.
        readDoneId = null;

        Set<String> skip = visited;
        if (!blocked.isEmpty()) {
            skip = new LinkedHashSet<>(visited);
            skip.addAll(blocked);
        }
        Interactables.Poi next = Interactables.nearestUnsolved(self.x, self.z, solved, skip);
        if (next != null) {
            goal = Goal.gotoPuzzle(next.id());
            return;
        }

        // 풀 게 없으면 쪽지를 읽으러 다닌다. 이게 없으면 자물쇠가 다 풀린 뒤로는 아래 따라가기만
        // 남아, 봇이 게임 내내 사람 뒤를 졸졸 따라다닌다.
        Interactables.Poi note = Interactables.nearestUnvisitedNote(self.x, self.z, skip);
        if (note != null) {
            goal = Goal.gotoNote(note.id());
            return;
        }

        // 닿는 쪽지를 다 읽었으면 기록을 비우고 곧바로 다시 고른다. 비우기만 하고 아래로
        // 떨어지면 그 tick에 따라가기 목표가 잡히고, 따라가기는 도착해도 끝나지 않아서
        // 두 번 다시 쪽지를 고를 일이 없다 — 초기화가 무의미해진다.
        if (!visited.isEmpty()) {
            visited.clear();
            Interactables.Poi again = Interactables.nearestUnvisitedNote(self.x, self.z, blocked);
            if (again != null) {
                log.debug("봇이 닿는 쪽지를 다 봤다 — 순회 다시 시작");
                goal = Goal.gotoNote(again.id());
                return;
            }
        }

        // 여기까지 오면 닿는 쪽지가 아예 없다(잠긴 방에 다 갇혀 있음). 그때만 사람을 따라간다.
        Player mate = nearestHuman(self, players);
        goal = mate == null ? Goal.IDLE : Goal.followPlayer(mate.id);
    }

    /**
     * 지금 닿을 수 있는 사람 중 최근접. 아무도 없으면 null(봇만 남았거나 다들 잠긴 감방 안).
     * 못 닿는 사람을 고르면 그 사람 감방문 앞에 붙어 멈춘다 — IDLE로 서 있는 편이 낫다.
     */
    private Player nearestHuman(Player self, Collection<Player> players) {
        Player best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Player p : players) {
            if (p.bot || blocked.contains(p.id)) {
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
            // 못 닿는 지점은 아예 안 보여준다. 프롬프트로 "가지 마라"고 부탁하는 것보다 확실하고,
            // valid()가 ctx.pois 기준이라 모델이 그래도 고르면 자동으로 무효 처리된다.
            if (blocked.contains(p.id())) {
                continue;
            }
            pois.add(new BotContext.PoiView(p.id(), p.x(), p.z(), p.label(), solved.contains(p.id())));
        }
        List<BotContext.MateView> mates = new ArrayList<>();
        for (Player p : players) {
            // 못 닿는 사람도 POI와 같은 이유로 감춘다(모델이 고르면 valid()가 걸러내기도 한다).
            if (!p.bot && !blocked.contains(p.id)) {
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
