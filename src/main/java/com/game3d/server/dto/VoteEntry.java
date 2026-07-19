package com.game3d.server.dto;

/** 서버 → 클라: 누가 누구를 AI로 지목했는지. 집계는 클라가 한다(표가 몇 안 된다). */
public record VoteEntry(String voterId, String targetId) {}
