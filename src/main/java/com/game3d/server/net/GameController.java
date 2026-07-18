package com.game3d.server.net;

import com.game3d.server.dto.DoorMessage;
import com.game3d.server.dto.InputMessage;
import com.game3d.server.dto.JoinMessage;
import com.game3d.server.dto.SolveMessage;
import com.game3d.server.dto.Vec3;
import com.game3d.server.game.Room;
import com.game3d.server.game.RoomManager;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * 실시간 입력 수신. 좌표가 아닌 "의도"만 받아 룸 상태에 반영한다(권위 서버).
 * 브로드캐스트는 GameLoop가 tick마다 담당하므로 여기서 응답을 보내지 않는다.
 *
 * STOMP 세션에 (roomId, playerId)를 저장해 두어, 연결 종료 시 이탈 처리에 쓴다.
 */
@Controller
public class GameController {

    static final String ATTR_ROOM = "roomId";
    static final String ATTR_PLAYER = "playerId";

    private final RoomManager roomManager;

    public GameController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    /** 클라 → 서버: 룸 입장. /app/rooms/{roomId}/join */
    @MessageMapping("/rooms/{roomId}/join")
    public void join(@DestinationVariable String roomId,
                     @Payload JoinMessage msg,
                     SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.getOrCreate(roomId);
        room.join(msg.id(), msg.nick());

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put(ATTR_ROOM, roomId);
            attrs.put(ATTR_PLAYER, msg.id());
        }
    }

    /** 클라 → 서버: 이동 의도. /app/rooms/{roomId}/input */
    @MessageMapping("/rooms/{roomId}/input")
    public void input(@DestinationVariable String roomId,
                      @Payload InputMessage msg,
                      SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        if (room == null || msg.move() == null) {
            return;
        }
        // input 메시지엔 id가 없다(클라 위조 방지). join 시 세션에 묶인 playerId를 신뢰한다.
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null || !(attrs.get(ATTR_PLAYER) instanceof String playerId)) {
            return;
        }
        Vec3 m = msg.move();
        room.input(playerId, m.x(), m.z(), msg.rotationY(), msg.seq(), System.currentTimeMillis());
    }

    /** 클라 → 서버: 퍼즐 해결(협동 동기화). /app/rooms/{roomId}/solve
     *  해결 상태는 GameLoop의 스냅샷(solvedIds)으로 방 전체에 전파된다. */
    @MessageMapping("/rooms/{roomId}/solve")
    public void solve(@DestinationVariable String roomId, @Payload SolveMessage msg) {
        Room room = roomManager.get(roomId);
        if (room != null && msg != null) {
            room.markSolved(msg.objectId());
        }
    }

    /** 클라 → 서버: 감방문 열림 토글. /app/rooms/{roomId}/door */
    @MessageMapping("/rooms/{roomId}/door")
    public void door(@DestinationVariable String roomId, @Payload DoorMessage msg) {
        Room room = roomManager.get(roomId);
        if (room != null && msg != null) {
            room.toggleDoor(msg.doorId());
        }
    }
}
