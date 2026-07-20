package com.game3d.server.game;

/**
 * 방 하나의 진행 단계 시계. 경과 시간만으로 현재 단계를 정한다.
 *
 * 전이를 이벤트로 쌓지 않고 "시작 시각 + 경과"로 매번 계산한다. 그래서 tick이 밀리거나
 * 한 tick에 두 단계를 건너뛸 만큼 늦어도 상태가 어긋나지 않는다.
 *
 * 스레드: 루프 스레드에서만 만지도록 {@link Room#tick}에서만 호출한다.
 */
class PhaseTimeline {

    private final PhaseProperties props;

    /**
     * 0이면 아직 시작 전(LOBBY). {@link #start}가 찍는다.
     *
     * 예전엔 첫 tick에서 자동으로 찍었는데, 방은 첫 사람이 대기방에 들어오면 만들어지므로
     * 아무도 시작을 누르지 않았는데 시계가 흐르기 시작했다(실측: 12초 뒤 들어온 사람이
     * 남은 시간 107.9초를 받음).
     */
    private long startedAtMs;
    private GamePhase current = GamePhase.LOBBY;

    /**
     * 순찰에 걸려 앞당겨진 시간(ms). 경과 시간에 더해 시계를 빨리 감는다.
     *
     * 단계 길이를 줄이지 않고 경과 쪽을 미는 이유: 길이를 건드리면 어느 단계에서 깎을지를
     * 정해야 하는데(지금 단계? 남은 전부?), 경과를 밀면 "자정이 그만큼 당겨진다"가 단계
     * 구분 없이 한 번에 성립한다. 남은 순찰 일정도 같은 시계를 보므로 함께 당겨진다.
     */
    private long penaltyMs;

    PhaseTimeline(PhaseProperties props) {
        this.props = props;
    }

    /** 아직 대기 중이면 true. */
    boolean notStarted() {
        return startedAtMs == 0;
    }

    /**
     * 게임 시작(멱등). 이 시점부터 시계가 흐른다.
     *
     * @return 이번 호출로 실제 시작됐으면 true. 이미 시작했으면 false.
     */
    boolean start(long nowMs) {
        if (startedAtMs != 0) {
            return false;
        }
        startedAtMs = nowMs;
        current = GamePhase.TIMELINE[0];
        return true;
    }

    /** 시계를 진행한다. 단계가 바뀌었으면 true. 시작 전이면 아무 일도 하지 않는다. */
    boolean advance(long nowMs) {
        if (startedAtMs == 0) {
            return false; // LOBBY에서 대기 — 시작 신호를 받을 때까지 시계를 세워 둔다
        }
        GamePhase next = phaseAt(nowMs);
        if (next != current) {
            current = next;
            return true;
        }
        return false;
    }

    GamePhase phase() {
        return current;
    }

    /**
     * 자정을 앞당긴다(순찰 적발 페널티).
     *
     * 남은 시간보다 크게 들어와도 막지 않는다 — 그러면 곧바로 ENDED가 되는데, 그건 "시간이
     * 다 됐다"는 뜻이라 규칙상 옳다.
     */
    void penalize(long ms) {
        if (ms > 0) {
            penaltyMs += ms;
        }
    }

    /** 게임 시작 기준 경과 시간(ms). 페널티가 반영된 값 — 단계 시계는 이것을 본다. */
    long elapsedMs(long nowMs) {
        return startedAtMs == 0 ? 0 : nowMs - startedAtMs + penaltyMs;
    }

    /**
     * 페널티를 <b>빼고</b> 실제로 흐른 시간(ms).
     *
     * 순찰 일정은 이쪽을 본다. 페널티가 반영된 시계로 순찰을 굴리면, 걸리는 순간 시계가
     * 앞으로 튀면서 진행 중이던 순찰 구간까지 지나가 버린다 — 걸린 사람이 오히려 순찰에서
     * 풀려나는 셈이라 벌이 아니라 상이 된다(2026-07-21 실측: 적발 즉시 ACTIVE → NONE).
     */
    long rawElapsedMs(long nowMs) {
        return startedAtMs == 0 ? 0 : nowMs - startedAtMs;
    }

    /** 현재 단계의 남은 시간(ms). ENDED거나 시작 전이면 0. */
    long remainMs(long nowMs) {
        if (startedAtMs == 0 || current == GamePhase.ENDED) {
            return 0;
        }
        long elapsed = elapsedMs(nowMs);
        long end = 0;
        for (GamePhase p : GamePhase.TIMELINE) {
            end += props.durationMs(p);
            if (elapsed < end) {
                return end - elapsed;
            }
        }
        return 0;
    }

    /** 경과 시간이 어느 단계에 해당하는지. 타임라인을 다 지나면 ENDED. */
    private GamePhase phaseAt(long nowMs) {
        long elapsed = elapsedMs(nowMs);
        long end = 0;
        for (GamePhase p : GamePhase.TIMELINE) {
            end += props.durationMs(p);
            if (elapsed < end) {
                return p;
            }
        }
        return GamePhase.ENDED;
    }
}
