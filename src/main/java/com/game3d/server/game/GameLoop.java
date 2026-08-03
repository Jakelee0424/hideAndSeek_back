package com.game3d.server.game;

import com.game3d.server.dto.ChatEvent;
import com.game3d.server.dto.WorldSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 권위 서버 게임 루프. 고정 주기(game.tick-ms)마다 모든 룸의 상태를 진행하고
 * 룸별 스냅샷을 /topic/rooms/{roomId}/state 로 브로드캐스트한다.
 *
 * 단일 스케줄러 스레드에서 전 룸을 순차 tick → 룸 상태 변경이 직렬화된다.
 * 룸 수/부하가 커지면 룸별 전용 루프로 분리할 것.
 */
@Component
public class GameLoop {

    private final RoomManager roomManager;
    private final SimpMessagingTemplate broker;

    public GameLoop(RoomManager roomManager, SimpMessagingTemplate broker) {
        this.roomManager = roomManager;
        this.broker = broker;
    }

    @Scheduled(fixedRateString = "${game.tick-ms}")
    public void tick() {
        long now = System.currentTimeMillis();
        for (Room room : roomManager.rooms()) {
            // 빈 방과 "끝난 지 오래된 방"을 치운다. 방 제거가 유일한 초기화 수단이라,
            // 사람이 남아 있다는 이유로 끝난 방을 계속 살려 두면 다음 판이 이전 판 상태로 시작된다.
            if (room.isEmpty() || room.expired(now)) {
                roomManager.remove(room.roomId(), now);
                continue;
            }
            room.tick(now);
            WorldSnapshot snap = room.snapshot(now);
            broker.convertAndSend("/topic/rooms/" + room.roomId() + "/state", snap);

            // 채팅은 스냅샷과 별도 토픽으로 나간다. 스냅샷은 "지금 상태"라 tick 사이에 오간
            // 말이 유실될 수 있는데, 채팅은 한 줄도 사라지면 안 된다.
            List<ChatEvent> chat = room.drainChat();
            if (!chat.isEmpty()) {
                String dest = "/topic/rooms/" + room.roomId() + "/chat";
                for (ChatEvent e : chat) {
                    broker.convertAndSend(dest, e);
                }
            }
        }
    }
}
