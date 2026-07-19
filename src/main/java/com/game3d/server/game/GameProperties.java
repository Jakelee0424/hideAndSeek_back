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
        long inputTimeoutMs,
        /**
         * 방 하나에 들어갈 수 있는 <b>사람</b> 수(AI 봇은 세지 않는다).
         *
         * 대기열(game.queue.capacity)은 서버 전체 동시 접속을 재는 값이라 방 정원이 아니다.
         * 방을 막는 건 여기다.
         */
        int maxPlayersPerRoom
) {
    public double tickSeconds() {
        return tickMs / 1000.0;
    }
}
