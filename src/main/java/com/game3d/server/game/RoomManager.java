package com.game3d.server.game;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 룸 레지스트리. 룸은 첫 입장 시 자동 생성된다(로비=프론트 라우팅). */
@Component
public class RoomManager {

    private final GameProperties props;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public RoomManager(GameProperties props) {
        this.props = props;
    }

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> new Room(id, props));
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public Collection<Room> rooms() {
        return rooms.values();
    }

    /** 빈 룸 정리(루프에서 주기 호출). */
    public void removeIfEmpty(String roomId) {
        rooms.computeIfPresent(roomId, (id, room) -> room.isEmpty() ? null : room);
    }
}
