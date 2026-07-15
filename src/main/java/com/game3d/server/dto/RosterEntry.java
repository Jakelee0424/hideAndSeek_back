package com.game3d.server.dto;

/**
 * 서버 → 클라: 정적 플레이어 정보(닉네임 등). 입·퇴장으로 로스터가 바뀔 때만 스냅샷에 실린다.
 * 매 tick 재전송하지 않아 20Hz 페이로드에서 제외된다.
 * 프론트 net/types.ts의 RosterEntry와 필드명 일치.
 */
public record RosterEntry(String id, String nick) {}
