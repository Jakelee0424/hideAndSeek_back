package com.game3d.server.dto;

import java.util.List;

/**
 * 서버 → 클라: 월드 스냅샷(tick마다 브로드캐스트). 프론트 WorldSnapshot과 일치.
 * solvedIds: 이 방에서 해결된 퍼즐 오브젝트 id 목록(협동 동기화).
 */
public record WorldSnapshot(long tick, List<PlayerState> players, List<String> solvedIds) {}
