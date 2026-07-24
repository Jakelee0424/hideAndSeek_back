package com.game3d.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 서버 → 클라: 정문 함정에 걸려 재수감된 한 명. WorldSnapshot.reimprisons에 실린다.
 * 펀치와 같은 규약 — 매 tick이 아니라 "함정이 발동한 순간"에만 실리는 이벤트다.
 *
 *   - victim: 다시 갇힌 사람 id. 본인이면 클라가 자기 예측 위치를 감방으로 하드 스냅한다
 *     (넉백처럼 작은 임펄스가 아니라 큰 순간이동이라 결정론적 복제로는 못 맞춘다).
 *   - x, z:   보내진 감방 위치(서버 권위). victim 본인 클라가 여기로 순간이동한다.
 *   - relock: 다시 잠긴 자물쇠 id("lock-A"…). 클라가 solved에서 지워 감방문을 도로 닫는다.
 *             (서버는 solvedIds에서 이미 지웠지만, 클라 syncSolved는 더하기만 해서 스스로는 못 지운다.)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReimprisonEvent(String victim, double x, double z, String relock) {}
