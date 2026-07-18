package com.game3d.server.dto;

/**
 * 서버 → 클라: tick마다 바뀌는 경량 플레이어 상태(20Hz 핫패스).
 * 정적 값(nick 등)은 싣지 않는다 → RosterEntry로 분리.
 * x/z/rot/y는 스냅샷 생성 시 반올림해 페이로드를 줄인다.
 * 프론트 net/types.ts의 PlayerTick과 필드명 일치.
 *
 * y는 **지면 위 높이**(m)다. 착지 상태면 0 — 서버 내부의 절대 좌표(캡슐 중심 y=0.5)가 아니라
 * 프론트의 "발바닥이 y=0" 규약에 맞춘 값이다. 점프가 남에게도 보이려면 이 값이 필요하다.
 * (점프 도입 전엔 항상 0이라 아예 싣지 않았다.)
 */
public record PlayerTick(String id, double x, double y, double z, double rot) {}
