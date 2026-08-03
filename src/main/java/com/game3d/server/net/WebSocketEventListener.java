package com.game3d.server.net;

import com.game3d.server.game.Room;
import com.game3d.server.game.RoomManager;
import com.game3d.server.game.WaitingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * WS 연결 종료 시, join에서 세션에 묶어둔 (roomId, playerId)로 룸에서 플레이어를 제거하고
 * 대기열 슬롯을 반납한다. 없으면 이탈해도 룸에 유령 플레이어가 남아 스냅샷에 계속 브로드캐스트되고,
 * 대기열 쪽은 자리가 영영 안 나서 뒤에 선 사람이 못 들어온다.
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomManager roomManager;
    private final WaitingQueue queue;

    public WebSocketEventListener(RoomManager roomManager, WaitingQueue queue) {
        this.roomManager = roomManager;
        this.queue = queue;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            return;
        }
        if (attrs.get(GameController.ATTR_ROOM) instanceof String roomId
                && attrs.get(GameController.ATTR_PLAYER) instanceof String playerId) {
            Room room = roomManager.get(roomId);
            if (room != null) {
                room.leave(playerId);
                // 이탈은 그 사람을 스냅샷 states에서 즉시 지운다 = 투표 후보에서도 사라진다.
                // "분명히 있던 사람이 투표에 안 보인다"의 유력한 경로라 인원과 함께 남긴다.
                log.info("방 {} 이탈 {} → 남은 사람 {}명", roomId, playerId, room.humanCount());
            }
            // 슬롯 반납 → 대기열 맨 앞 사람이 승급된다.
            queue.release(playerId);
        }
    }
}
