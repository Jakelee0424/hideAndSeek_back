package com.game3d.server.net;

import com.game3d.server.game.Room;
import com.game3d.server.game.RoomManager;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * WS 연결 종료 시, join에서 세션에 묶어둔 (roomId, playerId)로 룸에서 플레이어를 제거한다.
 * 없으면 이탈해도 룸에 유령 플레이어가 남아 스냅샷에 계속 브로드캐스트된다.
 */
@Component
public class WebSocketEventListener {

    private final RoomManager roomManager;

    public WebSocketEventListener(RoomManager roomManager) {
        this.roomManager = roomManager;
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
            }
        }
    }
}
