package com.game3d.server.dto;

/** 클라 → 서버: "이 사람이 AI인 것 같다" 투표. targetId는 로스터의 플레이어 id. */
public record VoteMessage(String targetId) {}
