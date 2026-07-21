package com.game3d.server.dto;

/**
 * 클라 → 서버: 인게임 채팅 발화. /app/rooms/{roomId}/chat
 *
 * 보낸 사람을 페이로드에 담지 않는다 — vote·punch와 같은 이유다. 클라가 senderId를 대면
 * 남의 이름으로 아무 말이나 흘릴 수 있고, 마지막 단계가 "누가 AI인가"를 말로 가리는
 * 투표라 발화자 위조는 게임 자체를 무너뜨린다. 누가 말했는지는 STOMP 세션에 묶인
 * playerId로만 정한다.
 *
 * ⚠️ 클라 → 서버 DTO에는 <b>primitive를 더하지 말 것</b>. Jackson 3은 "없는 필드 → null →
 *    primitive" 매핑을 실패로 보고 메시지 전체를 거부해, 구버전 클라의 채팅이 통째로 막힌다
 *    (InputMessage에 sprint/jump를 더했을 때 실제로 이동이 전부 막힌 전례가 있다).
 */
public record ChatMessage(String text) {}
