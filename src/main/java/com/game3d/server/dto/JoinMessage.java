package com.game3d.server.dto;

/**
 * 클라 → 서버: 룸 입장. 프론트 JoinMessage와 일치.
 *
 * token은 대기열(/api/queue)에서 받은 입장 토큰이다. 대기열이 꺼져 있거나 한산할 때는
 * 없어도 통과하므로(WaitingQueue.admitOnJoin) 옛 클라도 그대로 붙는다.
 */
public record JoinMessage(String id, String nick, String token) {}
