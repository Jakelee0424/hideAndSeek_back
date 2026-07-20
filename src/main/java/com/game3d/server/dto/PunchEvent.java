package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 서버 → 클라: 이 tick에 성사된 펀치 하나. WorldSnapshot.punches에 실린다.
 * 위치와 달리 매 tick 있는 값이 아니라 "일어난 순간"에만 실리는 이벤트다(로스터와 같은 규약).
 *
 * 두 가지 용도가 한 이벤트에 얹혀 있다:
 *   - attacker: 펀치 모션을 재생할 대상(원격 시청자와 본인). 헛방이어도 모션은 나간다.
 *   - victim/dir: 맞은 사람과 넉백 방향(단위 벡터, attacker→victim). victim이 <b>본인</b>이면
 *     클라가 같은 방향으로 자기 예측 위치에 넉백을 적용한다(서버와 동일 감쇠 → 결정론적 복제).
 *     맞은 사람이 없으면(헛방) victim은 null.
 *
 * 넉백 자체는 서버가 victim 위치에 적용하므로 <b>제3자</b>는 평소처럼 스냅샷 보간으로 밀림을
 * 본다. victim 본인만 예측 위치를 서버와 맞추려고 이 이벤트로 같은 힘을 재현한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PunchEvent(String attacker, String victim, double dirX, double dirZ) {}
