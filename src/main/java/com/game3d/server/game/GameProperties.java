package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 game.* 설정. 핫패스에서 매번 조회하지 않도록 값 캐시로 사용. */
@ConfigurationProperties(prefix = "game")
public record GameProperties(
        double speed,
        double sprintMultiplier,
        double jumpSpeed,
        double gravity,
        long tickMs,
        double halfExtent,
        long inputTimeoutMs
) {
    public double tickSeconds() {
        return tickMs / 1000.0;
    }
}
