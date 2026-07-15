package com.game3d.server.dto;

/**
 * 서버 → 클라: 정적 플레이어 정보(닉네임 등). 입·퇴장으로 로스터가 바뀔 때만 스냅샷에 실린다.
 * 매 tick 재전송하지 않아 20Hz 페이로드에서 제외된다.
 * 프론트 net/types.ts의 RosterEntry와 필드명 일치.
 *
 * bot: AI 봇 여부. 이동·렌더에선 봇도 사람과 똑같이 다뤄지지만(그래서 tick 상태엔 이 필드가 없다),
 * "누가 방장인가" 같은 사람 전용 판단에선 반드시 제외해야 한다 — 봇이 방장이 되면 사람에게
 * 시작 버튼이 뜨지 않는다(실제로 겪음). 로스터는 입·퇴장 때만 나가므로 20Hz 페이로드엔 영향 없다.
 */
public record RosterEntry(String id, String nick, boolean bot) {}
