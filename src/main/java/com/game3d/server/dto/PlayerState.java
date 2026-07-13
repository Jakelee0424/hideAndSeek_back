package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 서버가 확정한 단일 플레이어 상태. 스냅샷에 실려 브로드캐스트된다.
 * 프론트 net/types.ts의 PlayerState와 필드명 일치.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerState(
        String id,
        String nick,
        Vec3 position,
        double rotationY,
        Role role
) {}
