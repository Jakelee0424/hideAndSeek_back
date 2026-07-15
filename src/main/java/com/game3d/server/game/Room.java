package com.game3d.server.game;

import com.game3d.server.dto.PlayerState;
import com.game3d.server.dto.WorldSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 하나의 게임 룸. 플레이어 hot state를 메모리에 격리 보관하고, tick마다 위치를 확정한다.
 * 입력(join/input)은 여러 스레드에서, tick()은 루프 스레드 한 곳에서 호출된다.
 */
public class Room {

    /** AI 봇의 고정 id/닉. 스냅샷엔 일반 원격 플레이어처럼 실린다(프론트 무변경). */
    private static final String BOT_ID = "bot-1";
    private static final String BOT_NICK = "AI";

    private final String roomId;
    private final GameProperties props;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    // 해결된 퍼즐 오브젝트 id(협동 동기화). 컨트롤러 스레드에서 추가, 루프 스레드에서 스냅샷 읽기.
    private final Set<String> solvedIds = ConcurrentHashMap.newKeySet();
    private long tick;

    Room(String roomId, GameProperties props) {
        this.roomId = roomId;
        this.props = props;
    }

    public String roomId() {
        return roomId;
    }

    /**
     * 사람이 아무도 없으면 빈 방. 봇은 인원으로 세지 않는다.
     * (봇을 세면 봇만 남은 방이 영영 안 치워지고 루프에 계속 남는다)
     */
    public boolean isEmpty() {
        for (Player p : players.values()) {
            if (!p.bot) {
                return false;
            }
        }
        return true;
    }

    /** 입장(멱등). 이미 있으면 닉네임만 갱신. 스폰 위치는 원 위에 분산 배치. */
    public void join(String id, String nick) {
        players.computeIfAbsent(id, key -> {
            int index = players.size();
            double angle = index * 2.399963; // 황금각으로 겹치지 않게 분산
            double r = Math.min(6.0, 1.0 + index * 0.5);
            double sx = Math.cos(angle) * r;
            double sz = Math.sin(angle) * r;
            return new Player(key, nick, sx, sz);
        });
        spawnBot();
    }

    /** AI 봇 투입(멱등). 첫 사람이 들어오면 자동으로 같이 스폰된다. */
    public void spawnBot() {
        players.computeIfAbsent(BOT_ID, key -> new Player(key, BOT_NICK, 0, 0, true));
    }

    /** 이탈. */
    public void leave(String id) {
        players.remove(id);
    }

    /** 이동 의도 반영(권위 서버: 좌표가 아닌 방향만 받는다). */
    public void input(String id, double moveX, double moveZ, double rotationY, long seq, long nowMs) {
        Player p = players.get(id);
        if (p != null) {
            p.applyInput(moveX, moveZ, rotationY, seq, nowMs);
        }
    }

    /** 한 tick 진행: 각 플레이어의 이동 의도를 검증·적용하고 tick 카운터를 올린다. */
    public void tick(long nowMs) {
        double dt = props.tickSeconds();
        double speed = props.speed();
        long timeout = props.inputTimeoutMs();

        for (Player p : players.values()) {
            double mx;
            double mz;
            if (p.bot) {
                // 봇: STOMP 입력 대신 브레인이 이동 의도를 만든다. 이하 이동·충돌은 사람과 공유.
                double[] mv = p.brain.steer(p.x, p.z, solvedIds);
                mx = mv[0];
                mz = mv[1];
            } else {
                mx = p.inputMoveX(nowMs, timeout);
                mz = p.inputMoveZ(nowMs, timeout);
            }

            // 클라 입력 불신: 이동 벡터 크기를 1로 클램프(속도 핵 방지).
            double len = Math.hypot(mx, mz);
            if (len > 1.0) {
                mx /= len;
                mz /= len;
            }

            p.x += mx * speed * dt;
            p.z += mz * speed * dt;

            // 벽/장애물 충돌 해석(문은 해결 시 통과). 프론트 예측과 동일 로직.
            double[] r = Collision.resolve(p.x, p.z, solvedIds);
            p.x = r[0];
            p.z = r[1];

            if (p.bot) {
                // 봇은 진행 방향을 본다(프론트 LocalPlayer와 같은 규약). 정지 중엔 마지막 회전 유지.
                if (len > 1e-6) {
                    p.rotationY = Math.atan2(mx, mz);
                }
            } else {
                // 회전은 시각용 → 클라 값 수용.
                p.rotationY = p.desiredRotationY();
            }
        }
        tick++;
    }

    /** 퍼즐 해결 기록(협동). 방 생명주기 동안 유지된다. */
    public void markSolved(String objectId) {
        if (objectId != null && !objectId.isBlank()) {
            solvedIds.add(objectId);
        }
    }

    public WorldSnapshot snapshot() {
        List<PlayerState> list = new ArrayList<>(players.size());
        for (Player p : players.values()) {
            list.add(p.snapshot());
        }
        return new WorldSnapshot(tick, list, new ArrayList<>(solvedIds));
    }
}
