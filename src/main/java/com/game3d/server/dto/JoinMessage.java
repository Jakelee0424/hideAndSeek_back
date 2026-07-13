package com.game3d.server.dto;

/** 클라 → 서버: 룸 입장. 프론트 JoinMessage와 일치. */
public record JoinMessage(String id, String nick) {}
