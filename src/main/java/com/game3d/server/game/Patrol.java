package com.game3d.server.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 정기 순찰 — 한 판에 한두 번, 정해진 시간 동안 복도를 도는 방해 요소.
 *
 * 도는 동안 움직이거나 무언가를 건드리면 걸리고, 걸리면 자정이 앞당겨진다.
 * <b>시스템이 조작을 막지는 않는다</b> — 멈추는 건 플레이어 몫이라는 게 이 장치의 전부다.
 *
 * <h2>왜 미리 다 정해 두는가</h2>
 * 생성 시점에 순찰 시각을 전부 뽑아 둔다({@link #offsets}). {@link PhaseTimeline}과 같은
 * 이유다 — 매 tick "지금 몇 번째 순찰 구간인가"를 경과 시간으로 <b>계산</b>하면, tick이
 * 밀리거나 한 tick에 구간을 통째로 건너뛸 만큼 늦어도 상태가 어긋나지 않는다.
 * 경과 시간은 페널티가 반영된 게임 시계라, 자정이 당겨지면 남은 순찰도 함께 당겨진다.
 *
 * <p>스레드: 루프 스레드({@link Room#tick})에서만 만진다. {@link #reportSuspicious}만
 * 컨트롤러 스레드에서도 불릴 수 있어 그 경계만 synchronized로 잡았다.
 */
class Patrol {

    enum State {
        /** 순찰 없음. */
        NONE,
        /** 곧 온다 — 멈출 준비를 하는 시간. 이때는 움직여도 걸리지 않는다. */
        WARNING,
        /** 순찰 중. <b>간수 시야 안에서</b> 움직이거나 건드리면 걸린다. */
        ACTIVE
    }

    // ── 순찰 간수 ────────────────────────────────────────────────────
    // 예전엔 순찰이 도는 동안 맵 어디에 있든 움직이면 걸렸다("전체 맵 감지"). 지금은 간수가
    // 실제로 복도를 걷고, 그 부채꼴 시야 안에서 움직여야만 걸린다 — 시야 밖이면 뛰어도 안전하다.
    // 적발 트리거(이동·점프·상호작용)는 그대로다. 바뀐 건 범위뿐이다.

    /**
     * 간수가 도는 노선: 수감동~별관을 잇는 중앙 복도(z=17)를 왕복한다.
     * 감방 문(z=20/14)과 별관 방 문이 전부 이 복도로 열려 있어 플레이어 동선과 정면으로 부딪힌다.
     * 복도 정중앙이라 벽·소품에 걸리지 않아 길찾기 없이 직선 왕복으로 충분하다.
     */
    private static final double ROUTE_Z = 17.0;
    private static final double ROUTE_X_MIN = -36.0;
    private static final double ROUTE_X_MAX = 36.0;

    /**
     * 순찰 간수 하나. 복도를 왕복하며 진행 방향으로 부채꼴 시야를 갖는다.
     *
     * <p>좌표는 루프 스레드가 쓰고 컨트롤러 스레드가 읽는다({@link Room#solveByPlayer}가
     * 시야를 물어본다) — 그래서 volatile이다.
     */
    static final class Guard {
        volatile double x;
        final double z;
        /** 바라보는(=걷는) 방향. +1 동쪽, -1 서쪽. */
        volatile double dir;

        Guard(double x, double z, double dir) {
            this.x = x;
            this.z = z;
            this.dir = dir;
        }

        /** 프론트 규약(+z가 rot 0)에 맞춘 y축 회전. */
        double rot() {
            return Math.atan2(dir, 0);
        }
    }

    /** 순찰 중일 때만 채워진다. 회차가 끝나면 비운다. */
    private final List<Guard> guards = new CopyOnWriteArrayList<>();

    /** 순찰이 첫 단계(소등)가 끝난 뒤 이만큼 지나서부터 돌기 시작한다. */
    private static final long LEAD_MS = 20_000;
    /** 마지막 순찰이 끝나고 이만큼은 남겨 둔다(공유 단계 끝에 붙어 터지지 않게). */
    private static final long TAIL_MS = 20_000;

    private final PatrolProperties props;
    /** 게임 시작 기준 오프셋(ms). 오름차순. */
    private final long[] offsets;
    private final long[] durations;
    /** 순찰마다 봇이 실수하는가. 미리 뽑아 둬야 같은 순찰 안에서 판단이 흔들리지 않는다. */
    private final boolean[] botSlips;

    private State state = State.NONE;
    /** 지금(또는 직전) 순찰의 인덱스. -1이면 아직 하나도 안 돌았다. */
    private int index = -1;
    /** 이번 순찰에서 처음 걸린 사람. 순찰이 바뀔 때 비운다 — 페널티는 1회당 한 번뿐이다. */
    private String caughtId;

    Patrol(PatrolProperties props, PhaseProperties phaseProps) {
        this.props = props;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int count = props.enabled() ? props.pickCount(rnd) : 0;

        // 순찰이 도는 구간: 감방 탈출(MISSION) + 단서 공유(SHARING).
        // 소등은 규칙을 읽는 시간이고, 색출은 투표창이 떠 있어 움직임에 의미가 없다.
        long windowStart = phaseProps.durationMs(GamePhase.ONBOARDING) + LEAD_MS;
        long windowEnd = phaseProps.durationMs(GamePhase.ONBOARDING)
                + phaseProps.durationMs(GamePhase.MISSION)
                + phaseProps.durationMs(GamePhase.SHARING) - TAIL_MS;

        long span = windowEnd - windowStart;
        if (count <= 0 || span <= 0) {
            this.offsets = new long[0];
            this.durations = new long[0];
            this.botSlips = new boolean[0];
            return;
        }

        this.offsets = new long[count];
        this.durations = new long[count];
        this.botSlips = new boolean[count];

        // 구간을 횟수만큼 쪼개 각 칸에서 하나씩 뽑는다. 뽑는 범위를 "칸 길이 - 최소간격 - 지속"
        // 으로 줄여 두면 다음 칸에서 어떤 값이 나와도 간격이 보장된다(맨 앞에 붙어도 안전).
        long slot = span / count;
        for (int i = 0; i < count; i++) {
            long dur = props.pickDurationMs(rnd);
            long room = slot - props.minGap().toMillis() - dur;
            long jitter = room > 0 ? rnd.nextLong(0, room + 1) : 0;
            this.durations[i] = dur;
            this.offsets[i] = windowStart + i * slot + jitter;
            this.botSlips[i] = rnd.nextDouble() < props.botSlipChance();
        }
    }

    /**
     * 게임 시계를 반영해 상태를 갱신한다.
     *
     * @param elapsedMs 게임 시작 기준 <b>실제</b> 경과 시간. 페널티를 뺀 값이어야 한다 —
     *                  반영된 값을 주면 걸리는 순간 시계가 튀어 진행 중이던 순찰이 끝나 버린다
     *                  ({@link PhaseTimeline#rawElapsedMs} 주석 참고)
     * @param allowed   지금 단계가 순찰이 도는 구간인가(감방 탈출·단서 공유). 아니면 무조건 NONE
     * @return 상태나 순찰 회차가 바뀌었으면 true(스냅샷에 다시 실어야 한다)
     */
    boolean advance(long elapsedMs, boolean allowed) {
        State nextState = State.NONE;
        int nextIndex = -1;

        long warnMs = props.warn().toMillis();
        for (int i = 0; allowed && i < offsets.length; i++) {
            long start = offsets[i];
            long end = start + durations[i];
            if (elapsedMs >= start && elapsedMs < end) {
                nextState = State.ACTIVE;
                nextIndex = i;
                break;
            }
            if (elapsedMs >= start - warnMs && elapsedMs < start) {
                nextState = State.WARNING;
                nextIndex = i;
                break;
            }
        }

        if (nextState == state && nextIndex == index) {
            return false;
        }
        // 회차가 넘어갔으면 지난 순찰의 적발 기록은 버린다.
        if (nextIndex != index) {
            synchronized (this) {
                caughtId = null;
            }
        }
        state = nextState;
        index = nextIndex;

        // 간수는 순찰이 도는 동안만 존재한다. ACTIVE에 들어설 때 복도 양끝에 세우고,
        // 끝나면 치운다(예고 중에는 아직 안 보인다 — 예고는 "곧 온다"는 소리일 뿐이다).
        if (state == State.ACTIVE) {
            if (guards.isEmpty()) {
                spawnGuards();
            }
        } else {
            guards.clear();
        }
        return true;
    }

    /** 복도 양끝에서 서로를 향해 출발시킨다. 홀수면 서쪽에서 출발하는 쪽이 하나 더 많다. */
    private void spawnGuards() {
        List<Guard> fresh = new ArrayList<>();
        int count = Math.max(1, props.guardCount());
        for (int i = 0; i < count; i++) {
            boolean eastbound = i % 2 == 0;
            fresh.add(eastbound
                    ? new Guard(ROUTE_X_MIN, ROUTE_Z, 1)
                    : new Guard(ROUTE_X_MAX, ROUTE_Z, -1));
        }
        guards.addAll(fresh);
    }

    /** 간수를 노선 위에서 dt초만큼 걷게 한다. 끝에 닿으면 돌아선다. */
    void moveGuards(double dtSeconds) {
        double step = props.guardSpeed() * dtSeconds;
        for (Guard g : guards) {
            double nx = g.x + g.dir * step;
            if (nx > ROUTE_X_MAX) {
                nx = ROUTE_X_MAX;
                g.dir = -1;
            } else if (nx < ROUTE_X_MIN) {
                nx = ROUTE_X_MIN;
                g.dir = 1;
            }
            g.x = nx;
        }
    }

    List<Guard> guards() {
        return guards;
    }

    /**
     * 이 지점이 간수 하나라도의 시야에 들어 있는가.
     *
     * 거리 → 시야각 → 벽 순으로 거른다(뒤로 갈수록 비싸다). 벽 판정은
     * {@link Collision#lineClear}라 소품도 시야를 가린다 — 침대·작업대 뒤에 붙으면 안 보인다.
     *
     * <p>루프 스레드와 컨트롤러 스레드 양쪽에서 불린다. 간수 좌표가 volatile이라
     * 한 tick 어긋난 값을 읽을 수는 있어도(1/20초) 찢어진 값을 읽지는 않는다.
     */
    boolean sees(double px, double pz) {
        if (state != State.ACTIVE) {
            return false;
        }
        double range = props.viewRange();
        double cosHalfFov = Math.cos(Math.toRadians(props.viewFovDeg()) / 2);
        for (Guard g : guards) {
            double dx = px - g.x;
            double dz = pz - g.z;
            double dist = Math.hypot(dx, dz);
            if (dist > range) {
                continue;
            }
            // 코앞은 각도를 따지지 않는다(등 뒤 30cm까지 통과시키면 어이없어 보인다).
            if (dist > 0.5 && (dx * g.dir) / dist < cosHalfFov) {
                continue;
            }
            if (Collision.lineClear(g.x, g.z, px, pz)) {
                return true;
            }
        }
        return false;
    }

    State state() {
        return state;
    }

    boolean active() {
        return state == State.ACTIVE;
    }

    /** 이번 순찰(또는 예고)이 끝나기까지 남은 시간(ms). 순찰이 없으면 0. */
    long remainMs(long elapsedMs) {
        if (index < 0 || state == State.NONE) {
            return 0;
        }
        long end = state == State.WARNING ? offsets[index] : offsets[index] + durations[index];
        return Math.max(0, end - elapsedMs);
    }

    /** 이번 순찰에서 봇이 실수하는가(= 멈추지 않고 움직여 걸릴 수 있는가). */
    boolean botSlipsNow() {
        return state == State.ACTIVE && index >= 0 && botSlips[index];
    }

    /**
     * 수상한 움직임을 신고한다.
     *
     * @return 이번 순찰에서 <b>처음</b> 걸린 경우에만 true. 페널티는 순찰 1회당 한 번뿐이다 —
     *         셋이 동시에 움직였다고 3분을 깎으면 한 번의 실수로 판이 끝난다.
     */
    synchronized boolean reportSuspicious(String playerId) {
        if (state != State.ACTIVE || caughtId != null || playerId == null) {
            return false;
        }
        caughtId = playerId;
        return true;
    }

    /** 이번 순찰에서 걸린 사람의 id. 없으면 null. */
    synchronized String caughtId() {
        return caughtId;
    }
}
