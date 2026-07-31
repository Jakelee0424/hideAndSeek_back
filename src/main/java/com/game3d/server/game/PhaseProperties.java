package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml의 game.phases.* 설정. 단계별 길이.
 *
 * Duration으로 받으므로 yml에 "10m", "90s" 처럼 쓴다. 데모/테스트에서 "10s"로 줄여 통째로
 * 돌려볼 수 있어야 하기 때문에 상수로 박지 않았다.
 */
@ConfigurationProperties(prefix = "game.phases")
public record PhaseProperties(
        Duration intro,
        Duration play,
        Duration vote
) {

    /**
     * 단계 길이(ms). LOBBY·ENDED는 시간으로 넘어가지 않는다(각각 시작 신호·끝).
     *
     * intro는 여기 없다 — 단계가 아니라 PLAY 앞부분에 겹쳐 흐르는 도입 내레이션 길이다.
     */
    public long durationMs(GamePhase phase) {
        return switch (phase) {
            case PLAY -> play.toMillis();
            case VOTE -> vote.toMillis();
            case LOBBY, ENDED -> Long.MAX_VALUE;
        };
    }

    /**
     * 도입 내레이션 길이(ms). PLAY 시작과 동시에 프론트 OnboardingOverlay가 이만큼 돈다.
     *
     * 단계에서 빠졌는데도 서버가 이 값을 아는 이유는 <b>순찰</b> 때문이다. 순찰은 내레이션이
     * 끝난 뒤에 시작해야 한다 — 읽는 동안 간수가 지나가면 손도 못 댄 채 걸린다(옛 구조에서
     * ONBOARDING 단계 길이가 하던 역할을 이 값이 그대로 이어받는다. {@link Patrol} 참고).
     */
    public long introMs() {
        return intro.toMillis();
    }

    /** 전체 게임 길이(ms). 로그·표시용. */
    public long totalMs() {
        long sum = 0;
        for (GamePhase p : GamePhase.TIMELINE) {
            sum += durationMs(p);
        }
        return sum;
    }
}
