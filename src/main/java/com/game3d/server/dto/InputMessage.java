package com.game3d.server.dto;

/**
 * 클라 → 서버: 이동 "의도"(방향 벡터). 서버가 검증·적용한다(권위 서버).
 * move는 정규화된 방향(y는 미사용). 프론트 InputMessage와 일치.
 *
 * sprint/jump도 "의도"일 뿐이다 — 속도 배수는 game.sprint-multiplier가, 점프 성립 여부(접지 판정)는
 * 서버가 정한다.
 *
 * ⚠️ 필드가 없는 옛 클라도 받으려면 application.yml의
 *    spring.jackson.deserialization.fail-on-null-for-primitives=false 가 필요하다.
 *    Jackson 3 기본값으로는 "없는 필드 → null → boolean" 매핑이 실패해 메시지가 통째로 거부된다.
 */
public record InputMessage(long seq, Vec3 move, double rotationY, boolean sprint, boolean jump) {}
