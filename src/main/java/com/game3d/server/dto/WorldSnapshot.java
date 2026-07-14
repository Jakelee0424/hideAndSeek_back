package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 서버 → 클라: 월드 스냅샷(tick마다 브로드캐스트). 프론트 WorldSnapshot과 일치.
 *   states:    tick마다 바뀌는 경량 위치 상태(항상 포함).
 *   roster:    정적 정보(닉네임). 입·퇴장으로 바뀔 때만 포함, 그 외 null → JSON에서 생략.
 *   solvedIds: 이 방에서 해결된 퍼즐 오브젝트 id 목록(협동 동기화).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorldSnapshot(
        long tick,
        List<PlayerTick> states,
        List<RosterEntry> roster,
        List<String> solvedIds
) {}
