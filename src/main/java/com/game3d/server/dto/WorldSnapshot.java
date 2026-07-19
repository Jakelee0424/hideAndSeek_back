package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 서버 → 클라: 월드 스냅샷(tick마다 브로드캐스트). 프론트 WorldSnapshot과 일치.
 *   states:    tick마다 바뀌는 경량 위치 상태(항상 포함).
 *   roster:    정적 정보(닉네임). 입·퇴장으로 바뀔 때만 포함, 그 외 null → JSON에서 생략.
 *   solvedIds: 이 방에서 해결된 퍼즐 오브젝트 id 목록(협동 동기화).
 *   openDoors: 현재 열려 있는 감방문 id 목록(F 토글). 매 tick 포함.
 *   phase:     진행 단계(GamePhase 이름). 로스터와 같은 규약 — 바뀔 때와 입장 시에만 포함.
 *   phaseRemainMs: 그 단계의 남은 시간. 카운트다운은 클라가 이 값에서 자체 진행한다.
 *                  매 tick 실으면 20Hz 내내 따라붙고, 절대 시각을 주면 클라 시계 오차를 탄다.
 *   votes:     AI 지목 현황. 로스터와 같은 규약 — 바뀔 때만 포함.
 *   aiId:      진짜 AI의 id. <b>ENDED 단계에서만</b> 실린다. 그 전에 주면 투표가 무의미해진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorldSnapshot(
        long tick,
        List<PlayerTick> states,
        List<RosterEntry> roster,
        List<String> solvedIds,
        List<String> openDoors,
        String phase,
        Long phaseRemainMs,
        List<VoteEntry> votes,
        String aiId
) {}
