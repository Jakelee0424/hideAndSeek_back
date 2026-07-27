package com.game3d.server.dto;

/**
 * 서버 → 클라: 순찰 간수 하나의 tick 상태. 순찰이 도는 동안에만 실린다(그 밖에는 guards 자체가 null).
 *
 * 예전 순찰은 "도는 동안 맵 어디서든 움직이면 걸림"이라 클라가 그릴 게 배너뿐이었다.
 * 지금은 간수가 실제로 복도를 걷고 그 시야 안에서만 걸리므로, 어디를 보고 있는지가
 * 곧 규칙이다 — 위치·방향뿐 아니라 <b>시야 범위·각도까지</b> 함께 보낸다.
 *
 * <p>범위·각도를 매 tick 싣는 건 낭비처럼 보이지만(간수당 16바이트), 상수를 프론트에 복사해 두면
 * 서버 설정(game.patrol.view-*)을 바꿨을 때 화면의 부채꼴만 옛 값으로 남는다. 이 프로젝트가
 * 이미 여러 번 겪은 이중 관리 함정이라(Collision·Interactables 주석 참고) 값을 실어 보낸다.
 *
 * <p>rot은 PlayerTick과 같은 규약(+z가 0). 프론트 net/types.ts의 GuardTick과 필드명 일치.
 */
public record GuardTick(double x, double z, double rot, double range, double fovDeg) {}
