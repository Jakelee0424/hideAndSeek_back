package com.game3d.server.dto;

/** 클라 → 서버: 감방문 열림 상태 토글 요청. doorId는 프론트 prisonLayout.DOORS의 id(cell-A~D). */
public record DoorMessage(String doorId) {}
