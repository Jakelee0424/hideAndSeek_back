package com.game3d.server.game;

import java.util.List;

/**
 * 계획을 세울 시점의 월드 스냅샷(불변). 루프 스레드가 만들어 플래너 스레드로 넘긴다.
 *
 * tick마다 만들면 핫패스 할당이 되므로, 계획 요청 순간에만 만든다(수 초에 한 번).
 * 플래너는 다른 스레드에서 도는 동안 룸 상태가 바뀌어도 이 사본만 본다.
 *
 * @param visitedIds 봇이 이미 다녀온 POI id(도착 순). 봇에게 "쪽지는 이미 읽었다" 같은 기억을 주는
 *                   유일한 수단이다. 이게 없으면 모델은 매 호출을 첫 판단으로 여겨 같은 곳을 다시 고른다.
 *                   불변 사본이어야 한다 — 루프 스레드가 계속 원본에 추가하기 때문.
 */
record BotContext(double x, double z, List<PoiView> pois, List<MateView> mates, List<String> visitedIds) {

    record PoiView(String id, double x, double z, String label, boolean solved) {}

    /** 같은 방의 사람 플레이어(봇 제외). */
    record MateView(String id, String nick, double x, double z) {}
}
