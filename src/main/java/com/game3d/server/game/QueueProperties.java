package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml의 game.queue.* 설정. 접속 대기열(Virtual Waiting Room). */
@ConfigurationProperties(prefix = "game.queue")
public record QueueProperties(
        boolean enabled,
        int capacity,
        Duration tokenTtl
) {
}
