package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** 술래/숨는 사람. 프론트 net/types.ts의 Role("seeker" | "hider")과 일치(소문자 직렬화). */
public enum Role {
    SEEKER("seeker"),
    HIDER("hider");

    private final String json;

    Role(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
