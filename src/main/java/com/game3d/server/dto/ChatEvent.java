package com.game3d.server.dto;

/**
 * 서버 → 클라: 채팅 한 줄. /topic/rooms/{roomId}/chat 으로 나간다.
 *
 * 스냅샷(20Hz)에 싣지 않고 별도 토픽을 쓰는 이유:
 *   - 스냅샷은 "지금 상태"라 tick 사이에 오간 말이 유실될 수 있다. 채팅은 한 줄도 사라지면 안 된다.
 *   - 채팅은 저빈도라 20Hz 스냅샷을 부풀릴 이유가 없다.
 *
 * ⚠️ nick을 함께 싣는다. 클라도 로스터로 닉을 알지만, 로스터는 <b>바뀔 때만</b> 오므로
 *    중간 입장자가 아직 못 받았을 수 있다. 그때 이름이 빈 채로 뜨는 것보다 낫다.
 *
 * ⚠️ <b>bot 여부는 절대 싣지 않는다.</b> 로스터의 bot 플래그를 결말 전까지 false로 내보내는
 *    것과 같은 이유다 — 채팅에 정체가 묻어 나가면 AI 지목 투표가 성립하지 않는다.
 *
 * @param senderId 말한 사람의 playerId(세션에서 확정된 값). 자기 말 강조에 쓴다.
 * @param nick     말한 사람의 닉네임("죄수 4721" 형식).
 * @param text     본문(서버에서 길이 제한·트림을 마친 값).
 * @param at       서버 기준 발화 시각(ms). 클라는 표시 순서에만 쓰고 시계 비교는 하지 않는다.
 */
public record ChatEvent(String senderId, String nick, String text, long at) {}
