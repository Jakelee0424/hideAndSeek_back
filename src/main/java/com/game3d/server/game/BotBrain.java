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
 *   <li><b>빠른 층</b>({@link #steer}) — 매 tick. 현재 goal까지의 경로를 {@link Nav}로 풀어
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
     * 자물쇠 앞에 서서 "푸는 척" 머무는 시간(ms). 매번 이 범위에서 새로 뽑는다.
     *
     * 봇은 퍼즐 UI가 없어 사실 즉시 풀 수 있지만, 도착하자마자 문이 열리면 보는 사람에게
     * 대놓고 치트로 보인다. 잠깐 멈춰 있다가 열리면 사람이 푸는 모습과 구분되지 않는다.
     * ⚠️ 고정값(5초)이면 매번 정확히 같은 시간이라 그 자체가 규칙성이다 — 범위로 흔든다.
     */
    private static final long SOLVE_DWELL_MIN_MS = 4000;
    private static final long SOLVE_DWELL_MAX_MS = 9000;

    /**
     * 쪽지 앞에서 읽는 시늉으로 멈춰 있는 시간(ms). 역시 매번 새로 뽑는다.
     *
     * 없으면 도착하자마자 다음 쪽지로 튀어서, 봇이 감방 사이를 쉼 없이 왕복하는 것처럼 보인다.
     */
    private static final long READ_DWELL_MIN_MS = 1500;
    private static final long READ_DWELL_MAX_MS = 4000;

    /**
     * 다음 목표를 고를 때 "최근접" 대신 후보로 볼 가까운 곳의 수.
     *
     * 1이면 코스가 완전히 결정적이라 봇이 같은 길을 무한 반복한다(2026-08-01 실측: 52초 주기).
     * 셋 중 무작위면 사람처럼 왔다 갔다 하면서도 결국 전부 돈다.
     */
    private static final int PICK_POOL = 3;

    /** 순회 도중 목적 없이 사람 쪽으로 잠깐 붙어 보는 확률. 사람은 늘 최단 경로로만 다니지 않는다. */
    private static final double FOLLOW_CHANCE = 0.15;

    // 달리기(2026-08-01). 예전엔 봇이 늘 정확히 걷기 속도(6.0)라, 속도만 재도 사람과 구분됐다.
    // 사람은 급할 때 달린다(6.0 × 1.8 = 10.8) — 봇도 가끔 구간 달리기를 한다.
    private static final long SPRINT_LEN_MIN_MS = 2000;
    private static final long SPRINT_LEN_MAX_MS = 5000;
    private static final long SPRINT_GAP_MIN_MS = 8000;
    private static final long SPRINT_GAP_MAX_MS = 20000;

    /** 점프 간격(ms). 사람이 이따금 툭 뛰는 정도. 잦으면 그것대로 이상하다. */
    private static final long JUMP_GAP_MIN_MS = 15000;
    private static final long JUMP_GAP_MAX_MS = 40000;

    /** 정지. 호출부는 읽기만 한다(핫패스 할당 회피용 공유 상수). */
    private static final double[] STOP = {0, 0};

    /** null이면 스크립트로만 돈다(LLM 비활성/키 없음). */
    private final BotPlanner llm;
    private final long intervalMs;

    /** 펀치 설정(빈도·사거리). null이면 안 친다. */
    private final BotProperties.Punch punchCfg;
    /** 다음 펀치가 가능해지는 시각. 루프 스레드 전용. */
    private long punchReadyAtMs;

    /** 봇의 감정표현을 방으로 흘려보내는 통로. Room이 도배 제한·전송을 맡는다. */
    private final java.util.function.Consumer<String> onSay;

    /**
     * 마지막으로 내보낸 감정표현(hello|laugh|sad|angry). 같은 감정을 바로 연달아 반복하지
     * 않으려고 기억한다. 표현이 넷뿐이라 예전 문장용 "최근 N개" 창을 그대로 쓰면 넷을 다 쓴 뒤
     * 전부 걸려 영영 표현을 못 하게 된다 — 직전 하나만 비교한다. inFlight가 플래너 스레드를
     * 직렬화하므로 별도 동기화는 불필요.
     */
    private String lastEmote;

    /** 마지막으로 실제 감정표현한 시각(ms). 최소 간격 판정용. 플래너 스레드에서만 만진다. */
    private long lastSayAtMs;
    /** 봇 감정표현 최소 간격(ms). 이 안에는 새 표현을 내보내지 않는다 — 짧은 사이에 연달아
     *  쏟아내면 사람 같지 않다. 0이면 매 계획마다 표현해 수다스러워진다.
     *  ⚠️ 0이나 너무 큰 값 금지: 봇이 아예 표현을 안 하면 "한 번도 표현 안 한 놈 = AI"가 되어
     *  투표가 무너진다. */
    private static final long SAY_COOLDOWN_MS = 25000;

    /** 호출 중복 방지. 응답이 주기보다 느려도 요청이 쌓이지 않는다. */
    private final AtomicBoolean inFlight = new AtomicBoolean();

    private volatile Goal goal = Goal.IDLE;
    private volatile long lastPlanAtMs;

    // 목표 좌표: 루프 스레드만 쓴다(tick마다 배열 새로 만들지 않으려고 필드로 둔다).
    // targetFeetY는 목표의 층이다 — POI는 전부 1층(0)이고, 사람을 따라갈 때만 그 사람 높이가 들어온다.
    private double targetX;
    private double targetZ;
    private double targetFeetY;

    /** 이번 tick에 못 닿는 POI. 루프 스레드가 steer 진입 때 채우고 그 안에서만 읽는다. */
    private Set<String> blocked = Set.of();

    // "푸는 중"인 자물쇠와 그 완료 시각. 루프 스레드 전용.
    private String solvingId;
    private long solvingUntilMs;

    // 달리기 구간. 루프 스레드 전용(steer/wantsSprint 둘 다 tick에서 불린다).
    private long sprintUntilMs;
    private long nextSprintAtMs;
    /** 다음 점프 시각. 점프는 순간이라 구간이 아니라 시점 하나면 된다. */
    private long nextJumpAtMs;

    /** [min,max) 사이 무작위 ms. 봇의 규칙성을 흐리는 데만 쓰므로 재현성은 필요 없다. */
    private static long jitter(long min, long max) {
        return min + (long) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (max - min));
    }

    /**
     * 지금 달릴지. Room.tick이 사람의 sprint 입력과 같은 자리에서 읽는다.
     *
     * 이동 중일 때만 의미가 있다(정지 중엔 속도를 곱해도 0). 구간을 두는 이유는, 매 tick
     * 확률을 굴리면 속도가 20Hz로 깜빡여 사람 눈에 미끄러지듯 보이기 때문이다.
     */
    boolean wantsSprint(long nowMs) {
        if (nowMs < sprintUntilMs) {
            return true;
        }
        if (nextSprintAtMs == 0) {
            nextSprintAtMs = nowMs + jitter(SPRINT_GAP_MIN_MS, SPRINT_GAP_MAX_MS);
            return false;
        }
        if (nowMs >= nextSprintAtMs) {
            sprintUntilMs = nowMs + jitter(SPRINT_LEN_MIN_MS, SPRINT_LEN_MAX_MS);
            nextSprintAtMs = sprintUntilMs + jitter(SPRINT_GAP_MIN_MS, SPRINT_GAP_MAX_MS);
            return true;
        }
        return false;
    }

    /**
     * 지금 점프할지. 맵에 점프 없이 못 가는 곳은 없으니 순전히 "사람처럼 보이려는" 동작이다.
     *
     * @param watched 간수 시야 안이면 뛰지 않는다 — 사람도 그때는 얌전히 있는다. 순찰 적발 판정은
     *                Room이 이 값을 그대로 써서 사람·봇을 <b>같은 조건으로</b> 본다.
     */
    boolean wantsJump(long nowMs, boolean watched) {
        if (watched) {
            return false;
        }
        if (nextJumpAtMs == 0) {
            nextJumpAtMs = nowMs + jitter(JUMP_GAP_MIN_MS, JUMP_GAP_MAX_MS);
            return false;
        }
        if (nowMs >= nextJumpAtMs) {
            nextJumpAtMs = nowMs + jitter(JUMP_GAP_MIN_MS, JUMP_GAP_MAX_MS);
            return true;
        }
        return false;
    }

    // 쪽지를 읽느라 멈춰 있는 시각까지와, 이번 목표에서 이미 읽기를 마쳤는지. 루프 스레드 전용.
    // readDoneId가 없으면 읽기가 끝나자마자 같은 쪽지에서 또 읽기가 걸려 영영 못 떠난다.
    private long readingUntilMs;
    private String readDoneId;

    // 길찾기 결과 캐시(루프 스레드 전용). Nav는 격자 BFS + 시야 판정이라 한 번이 공짜는 아니므로
    // 매 tick(20Hz) 돌리면 1 vCPU 서버엔 부담이다. 아래 조건에서만 다시 푼다.
    private double navX;
    private double navZ;
    private long navAtMs;
    private double navForX;
    private double navForZ;
    private double navForFeetY;

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

    /**
     * @param onSay 봇이 흘릴 감정표현 토큰("emote:<id>")을 받는 곳(Room::botSay). 플래너
     *              스레드(가상 스레드)에서 호출되므로 받는 쪽은 스레드 안전해야 한다.
     */
    BotBrain(BotPlanner llm, long intervalMs, BotProperties.Punch punchCfg,
             java.util.function.Consumer<String> onSay) {
        this.llm = llm;
        this.intervalMs = intervalMs;
        this.punchCfg = punchCfg;
        this.onSay = onSay;
    }

    /**
     * 지금 앞에 있는 사람을 툭 칠지. Room.tick이 매 tick 묻고, true면 사람의 punch 요청과
     * <b>같은 자리</b>({@code Player.requestPunch})에 세운다 — 사거리·전방 콘·쿨다운 판정은
     * 그대로 {@code Room.resolvePunches}가 사람과 똑같이 한다.
     *
     * <p>여기서 보는 건 "칠 마음이 있는가"뿐이다: 사거리 안에 사람이 있고, 대충 그쪽을 보고 있고,
     * 쉬는 시간이 지났고, 확률에 걸렸는가.
     *
     * @param patrolling 순찰 중이면 안 친다. 사람은 순찰 중 폭행이 곧 적발이라 자정이 깎이는데
     *                   ({@code Room.punch}의 catchSuspicious), 봇이 팀에 그 벌을 안기면 안 된다.
     */
    boolean wantsPunch(Player self, Collection<Player> players, long nowMs, boolean patrolling) {
        if (punchCfg == null || !punchCfg.enabled() || patrolling) {
            return false;
        }
        if (punchReadyAtMs == 0) {
            punchReadyAtMs = nowMs + punchCfg.minGapMs(); // 판 시작하자마자 치지는 않는다
            return false;
        }
        if (nowMs < punchReadyAtMs) {
            return false;
        }
        double fx = Math.sin(self.rotationY);
        double fz = Math.cos(self.rotationY);
        for (Player o : players) {
            if (o.bot) {
                continue;
            }
            double ex = o.x - self.x;
            double ez = o.z - self.z;
            double d = Math.hypot(ex, ez);
            if (d > punchCfg.range() || d < 1e-4) {
                continue;
            }
            if ((ex * fx + ez * fz) / d < 0.5) {
                continue; // 앞에 없다 — 뒤통수를 노리는 그림은 사람 같지 않다
            }
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= punchCfg.chance()) {
                return false; // 기회는 있었지만 안 쳤다. 다음 기회에 다시 굴린다
            }
            punchReadyAtMs = nowMs + punchCfg.minGapMs();
            return true;
        }
        return false;
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
                solvingUntilMs = nowMs + jitter(SOLVE_DWELL_MIN_MS, SOLVE_DWELL_MAX_MS);
                log.info("봇이 {} 앞에서 여는 중", arrived.id());
                return STOP;
            }

            // 스스로 풀 수 없는 것(쪽지, 그리고 사람이 열어야 하는 탈옥문) 앞에서는 잠깐 살펴본다.
            // solvable 기준으로 잡으면 탈옥문이 위 해제 분기와 여기 사이로 빠져 아무 대기 없이
            // 도착하자마자 돌아선다. readDoneId로 "이번 목표에선 이미 봤다"를 표시하지 않으면
            // 대기가 끝난 다음 tick에 같은 자리에서 또 걸려 그 지점을 영영 못 떠난다.
            if (arrived != null && !arrived.botSolvable() && !arrived.id().equals(readDoneId)) {
                readingUntilMs = nowMs + jitter(READ_DWELL_MIN_MS, READ_DWELL_MAX_MS);
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
        double feetY = self.y - Player.GROUND_Y;
        boolean stale = nowMs - navAtMs >= NAV_REFRESH_MS
                || Math.hypot(targetX - navForX, targetZ - navForZ) > NAV_TARGET_MOVED
                || Math.hypot(navX - self.x, navZ - self.z) < NAV_REACHED
                // 층이 바뀌면(계단을 올랐다) 경로를 즉시 다시 푼다 — 옛 경로는 아래층 것이다.
                || Math.abs(feetY - navForFeetY) > Collision.STEP_UP;
        if (!stale) {
            return;
        }
        double[] p = Nav.steerPoint(self.x, self.z, self.y - Player.GROUND_Y,
                targetX, targetZ, targetFeetY);
        navX = p[0];
        navZ = p[1];
        navForX = targetX;
        navForZ = targetZ;
        navForFeetY = feetY;
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
                targetFeetY = 0; // POI는 전부 1층이다
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
                        // 2층에 있는 사람도 따라간다 — 격자가 계단을 알아서 태워 준다.
                        // (웨이포인트 시절엔 층 개념이 없어 그 사람 **아래층**으로 걸어갔다.)
                        targetFeetY = p.y - Player.GROUND_Y;
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

        // 가끔은 목적 없이 사람 쪽으로 붙어 본다. 사람도 늘 최단 코스로만 다니지 않는다.
        // (여기서 고른 따라가기는 도착해도 안 끝나지만, 다음 계획 주기에 새 목표로 덮인다.)
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < FOLLOW_CHANCE) {
            Player buddy = nearestHuman(self, players);
            if (buddy != null) {
                goal = Goal.followPlayer(buddy.id);
                return;
            }
        }

        // ⚠️ 최근접 하나가 아니라 **가까운 셋 중 무작위**로 고른다. 최근접만 고르면 코스가
        // 결정적이라 봇이 같은 길을 무한 반복한다(2026-08-01 실측: 52초 주기로 완전히 동일).
        Interactables.Poi next = pick(Interactables.nearestUnvisited(
                self.x, self.z, skip, PICK_POOL, true, solved));
        if (next != null) {
            goal = Goal.gotoPuzzle(next.id());
            return;
        }

        // 풀 게 없으면 쪽지를 읽으러 다닌다. 이게 없으면 자물쇠가 다 풀린 뒤로는 아래 따라가기만
        // 남아, 봇이 게임 내내 사람 뒤를 졸졸 따라다닌다.
        Interactables.Poi note = pick(Interactables.nearestUnvisited(
                self.x, self.z, skip, PICK_POOL, false, solved));
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

    /** 후보 목록에서 하나를 무작위로. 비었으면 null. */
    private static Interactables.Poi pick(List<Interactables.Poi> cand) {
        if (cand.isEmpty()) {
            return null;
        }
        return cand.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(cand.size()));
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
                // 감정표현은 목표가 무효였어도 내보낸다. 둘은 별개다 — 엉뚱한 곳을 고른 계획이라고
                // 해서 감정 표현까지 버릴 이유는 없고, 봇이 아무 표현도 안 하면 그 자체가 정체를
                // 드러내는 신호가 된다.
                if (planned != null && onSay != null) {
                    String emote = planned.emote();
                    if (emote != null && !emote.isBlank()) {
                        long sayNow = System.currentTimeMillis();
                        // 같은 감정을 바로 연달아 반복하지 않고(사람 같지 않다), 최소 간격을 둔다
                        // (도배 방지). inFlight 덕에 이 블록은 직렬 실행이라 별도 동기화는 불필요.
                        if (!emote.equals(lastEmote) && sayNow - lastSayAtMs >= SAY_COOLDOWN_MS) {
                            lastEmote = emote;
                            lastSayAtMs = sayNow;
                            // 사람 클라가 보내는 것과 같은 토큰으로 흘린다. 프론트 net/emotes.ts의
                            // WIRE_PREFIX("emote:") + EmoteId 규약과 이중 관리 — 한쪽 고치면 양쪽.
                            onSay.accept("emote:" + emote);
                        }
                    }
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
