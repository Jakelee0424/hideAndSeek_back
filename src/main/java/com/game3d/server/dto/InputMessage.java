package com.game3d.server.dto;

/**
 * 클라 → 서버: 이동 "의도"(방향 벡터). 서버가 검증·적용한다(권위 서버).
 * move는 정규화된 방향(y는 미사용). 프론트 InputMessage와 일치.
 */
public record InputMessage(long seq, Vec3 move, double rotationY) {}
