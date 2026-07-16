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

    /** 0이면 아직 시작 전. 첫 tick(=첫 사람 입장 직후)에 찍힌다. */
    private long startedAtMs;
    private GamePhase current = GamePhase.TIMELINE[0];

    PhaseTimeline(PhaseProperties props) {
        this.props = props;
    }

    /**
     * 시계를 진행한다. 단계가 바뀌었으면 true.
     * 첫 호출에서 시작 시각을 찍는다 — 방은 첫 사람이 들어와야 tick되므로 입장 시점과 같다.
     */
    boolean advance(long nowMs) {
        if (startedAtMs == 0) {
            startedAtMs = nowMs;
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
