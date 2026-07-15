package com.game3d.server.game;

import java.util.List;

/**
 * 계획을 세울 시점의 월드 스냅샷(불변). 루프 스레드가 만들어 플래너 스레드로 넘긴다.
 *
 * tick마다 만들면 핫패스 할당이 되므로, 계획 요청 순간에만 만든다(수 초에 한 번).
 * 플래너는 다른 스레드에서 도는 동안 룸 상태가 바뀌어도 이 사본만 본다.
 */
record BotContext(double x, double z, List<PoiView> pois, List<MateView> mates, String lastArrivedId) {

    record PoiView(String id, double x, double z, String label, boolean solved) {}

    /** 같은 방의 사람 플레이어(봇 제외). */
    record MateView(String id, String nick, double x, double z) {}
}
