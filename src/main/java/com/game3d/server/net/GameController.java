package com.game3d.server.net;

import com.game3d.server.dto.ChatMessage;
import com.game3d.server.dto.DoorMessage;
import com.game3d.server.dto.InputMessage;
import com.game3d.server.dto.JoinMessage;
import com.game3d.server.dto.ReadyMessage;
import com.game3d.server.dto.SolveMessage;
import com.game3d.server.dto.Vec3;
import com.game3d.server.dto.VoteMessage;
import com.game3d.server.game.Room;
import com.game3d.server.game.RoomManager;
import com.game3d.server.game.WaitingQueue;
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
    private final WaitingQueue queue;

    public GameController(RoomManager roomManager, WaitingQueue queue) {
        this.roomManager = roomManager;
        this.queue = queue;
    }

    /**
     * 클라 → 서버: 룸 입장. /app/rooms/{roomId}/join
     *
     * 대기열 게이트를 여기서 통과시킨다. 대기열 REST만 두고 join을 검사하지 않으면
     * 대기 화면을 건너뛰고 곧장 STOMP로 붙는 것을 막을 수 없어 게이트가 장식이 된다.
     */
    @MessageMapping("/rooms/{roomId}/join")
    public void join(@DestinationVariable("roomId") String roomId,
                     @Payload JoinMessage msg,
                     SimpMessageHeaderAccessor accessor) {
        if (!queue.admitOnJoin(msg.id(), msg.token())) {
            return; // 정원 초과. 클라는 대기열 REST로 순번을 받아 기다린다.
        }

        Room room = roomManager.getOrCreate(roomId);
        if (!room.join(msg.id(), msg.nick())) {
            // 방 정원 초과. 잡아둔 대기열 자리를 돌려주지 않으면 아무도 못 쓰는 유령 자리가 된다.
            queue.release(msg.id());
            return; // 세션에도 묶지 않는다 — 이후 input/solve가 통과되면 안 된다.
        }

        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put(ATTR_ROOM, roomId);
            attrs.put(ATTR_PLAYER, msg.id());
        }
    }

    /** 클라 → 서버: 이동 의도. /app/rooms/{roomId}/input */
    @MessageMapping("/rooms/{roomId}/input")
    public void input(@DestinationVariable("roomId") String roomId,
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
        room.input(playerId, m.x(), m.z(), msg.rotationY(), msg.sprint(), msg.jump(),
                msg.seq(), System.currentTimeMillis());
    }

    /**
     * 클라 → 서버: 펀치. /app/rooms/{roomId}/punch
     *
     * input과 같은 이유로 페이로드를 받지 않는다 — 누가 쳤는지는 세션에 묶인 playerId로 정하고,
     * 누구를 맞혔는지·넉백 방향은 서버가 위치로 계산한다(클라가 대상을 지정하면 위조가 가능하다).
     */
    @MessageMapping("/rooms/{roomId}/punch")
    public void punch(@DestinationVariable("roomId") String roomId,
                      SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (room == null || attrs == null
                || !(attrs.get(ATTR_PLAYER) instanceof String playerId)) {
            return;
        }
        room.punch(playerId);
    }

    /** 클라 → 서버: 퍼즐 해결(협동 동기화). /app/rooms/{roomId}/solve
     *  해결 상태는 GameLoop의 스냅샷(solvedIds)으로 방 전체에 전파된다.
     *
     *  누가 풀었는지도 함께 넘긴다 — 순찰 중이었다면 그 행동 자체가 적발 사유이기 때문이다.
     *  id는 input과 같은 이유로 세션에서 가져온다(클라가 남의 이름을 댈 수 없게). */
    @MessageMapping("/rooms/{roomId}/solve")
    public void solve(@DestinationVariable("roomId") String roomId, @Payload SolveMessage msg,
                      SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        if (room == null || msg == null) {
            return;
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String playerId = attrs != null && attrs.get(ATTR_PLAYER) instanceof String id ? id : null;
        room.solveByPlayer(playerId, msg.objectId());
    }

    /** 클라 → 서버: 대기방 준비 토글. /app/rooms/{roomId}/ready */
    @MessageMapping("/rooms/{roomId}/ready")
    public void ready(@DestinationVariable("roomId") String roomId,
                      @Payload ReadyMessage msg,
                      SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (room == null || msg == null || attrs == null
                || !(attrs.get(ATTR_PLAYER) instanceof String playerId)) {
            return;
        }
        room.setReady(playerId, msg.readyOrTrue());
    }

    /**
     * 클라 → 서버: 게임 시작. /app/rooms/{roomId}/start
     *
     * 전원이 준비되지 않았으면 조용히 무시한다. 시작하면 단계가 LOBBY를 벗어나고, 그 전환이
     * 스냅샷으로 방 전체에 퍼져 모두가 함께 게임 화면으로 넘어간다 — 누른 사람만 넘어가면
     * 나머지는 대기방에 남는다(예전 동작).
     */
    @MessageMapping("/rooms/{roomId}/start")
    public void start(@DestinationVariable("roomId") String roomId) {
        Room room = roomManager.get(roomId);
        if (room != null) {
            room.requestStart();
        }
    }

    /**
     * 클라 → 서버: AI 지목 투표. /app/rooms/{roomId}/vote
     *
     * 투표자는 input과 같은 이유로 세션에 묶인 playerId를 쓴다 — 페이로드로 받으면
     * 남의 이름으로 아무 표나 던질 수 있다.
     */
    @MessageMapping("/rooms/{roomId}/vote")
    public void vote(@DestinationVariable("roomId") String roomId,
                     @Payload VoteMessage msg,
                     SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        if (room == null || msg == null) {
            return;
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null || !(attrs.get(ATTR_PLAYER) instanceof String voterId)) {
            return;
        }
        room.castVote(voterId, msg.targetId());
    }

    /**
     * 클라 → 서버: 인게임 채팅. /app/rooms/{roomId}/chat
     *
     * 말한 사람은 input·vote와 같은 이유로 세션에 묶인 playerId를 쓴다. 여기서 페이로드의
     * id를 믿으면 남의 이름으로 발언을 지어낼 수 있는데, 마지막 단계가 말을 근거로 AI를
     * 가리는 투표라 발화자 위조는 게임을 통째로 무너뜨린다.
     *
     * 길이 제한·도배 제한·전송은 Room이 맡는다(GameLoop이 tick 뒤에 브로드캐스트).
     */
    @MessageMapping("/rooms/{roomId}/chat")
    public void chat(@DestinationVariable("roomId") String roomId,
                     @Payload ChatMessage msg,
                     SimpMessageHeaderAccessor accessor) {
        Room room = roomManager.get(roomId);
        if (room == null || msg == null) {
            return;
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null || !(attrs.get(ATTR_PLAYER) instanceof String playerId)) {
            return;
        }
        room.chat(playerId, msg.text());
    }

    /** 클라 → 서버: 감방문 열림 토글. /app/rooms/{roomId}/door */
    @MessageMapping("/rooms/{roomId}/door")
    public void door(@DestinationVariable("roomId") String roomId, @Payload DoorMessage msg) {
        Room room = roomManager.get(roomId);
        if (room != null && msg != null) {
            room.toggleDoor(msg.doorId());
        }
    }
}
