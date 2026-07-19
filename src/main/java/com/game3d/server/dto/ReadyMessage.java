package com.game3d.server.dto;

/**
 * 클라 → 서버: 대기방 준비 토글.
 *
 * Boolean(래퍼)인 이유는 구버전 클라가 이 필드 없이 보내도 메시지 전체가 거부되지 않게 하려는 것이다
 * — Jackson은 "없는 필드 → null → primitive"를 실패로 보고 메시지를 통째로 버린다.
 */
public record ReadyMessage(Boolean ready) {

    /** 값이 없으면 준비된 것으로 본다(예전 클라의 "준비" 버튼은 토글이 아니었다). */
    public boolean readyOrTrue() {
        return ready == null || ready;
    }
}
