package com.game3d.server.dto;

/** 클라 → 서버: 퍼즐 해결 알림. objectId는 프론트 interactables의 오브젝트 id. */
public record SolveMessage(String objectId) {}
