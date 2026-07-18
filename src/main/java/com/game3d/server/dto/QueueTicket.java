package com.game3d.server.dto;

/**
 * 서버 → 클라: 대기열 표. 프론트 net/queue.ts의 QueueTicket과 필드명 일치.
 *
 * @param status   ADMITTED면 token으로 바로 입장, WAITING이면 position을 보여주고 폴링을 계속한다.
 * @param position 대기 순번(1부터). ADMITTED면 0.
 * @param token    입장 토큰. WAITING이면 null. STOMP join에 실어 보내야 한다.
 * @param waiting  현재 대기 인원(내 뒤까지 포함) — 대기 화면에 "N명 대기 중"으로 쓴다.
 * @param capacity 동시 입장 정원. 클라는 표시용으로만 쓴다.
 * @param active   현재 입장해 있는 인원.
 */
public record QueueTicket(
        Status status,
        int position,
        String token,
        int waiting,
        int capacity,
        int active
) {
    public enum Status { ADMITTED, WAITING }

    public static QueueTicket admitted(String token, int waiting, int capacity, int active) {
        return new QueueTicket(Status.ADMITTED, 0, token, waiting, capacity, active);
    }

    public static QueueTicket waiting(int position, int waiting, int capacity, int active) {
        return new QueueTicket(Status.WAITING, position, null, waiting, capacity, active);
    }
}
