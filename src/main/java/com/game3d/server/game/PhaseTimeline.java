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

    /** 현재 단계의 남은 시간(ms). ENDED거나 시작 전이면 0. */
    long remainMs(long nowMs) {
        if (startedAtMs == 0 || current == GamePhase.ENDED) {
            return 0;
        }
        long elapsed = nowMs - startedAtMs;
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
        long elapsed = nowMs - startedAtMs;
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
