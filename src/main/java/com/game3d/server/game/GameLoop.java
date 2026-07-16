package com.game3d.server.game;

import com.game3d.server.dto.WorldSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
            if (room.isEmpty()) {
                roomManager.removeIfEmpty(room.roomId());
                continue;
            }
            room.tick(now);
            WorldSnapshot snap = room.snapshot(now);
            broker.convertAndSend("/topic/rooms/" + room.roomId() + "/state", snap);
        }
    }
}
