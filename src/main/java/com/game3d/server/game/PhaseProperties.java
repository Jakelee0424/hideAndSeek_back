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
        Duration onboarding,
        Duration mission,
        Duration sharing,
        Duration vote
) {

    /** 단계 길이(ms). ENDED는 길이가 없다(영원히 지속). */
    public long durationMs(GamePhase phase) {
        return switch (phase) {
            case ONBOARDING -> onboarding.toMillis();
            case MISSION -> mission.toMillis();
            case SHARING -> sharing.toMillis();
            case VOTE -> vote.toMillis();
            case ENDED -> Long.MAX_VALUE;
        };
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
