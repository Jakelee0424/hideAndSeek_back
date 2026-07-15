package com.game3d.server.dto;

/**
 * 서버 → 클라: tick마다 바뀌는 경량 플레이어 상태(20Hz 핫패스).
 * 정적 값(nick 등)과 상수 y는 싣지 않는다 → RosterEntry로 분리.
 * x/z/rot은 스냅샷 생성 시 반올림해 페이로드를 줄인다.
 * 프론트 net/types.ts의 PlayerTick과 필드명 일치.
 */
public record PlayerTick(String id, double x, double z, double rot) {}
