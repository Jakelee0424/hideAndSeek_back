package com.game3d.server.game;

import com.game3d.server.dto.PlayerTick;
import com.game3d.server.dto.RosterEntry;
import com.game3d.server.dto.Role;

/**
 * 룸 안의 가변(hot) 플레이어 상태. DB가 아닌 메모리에 둔다.
 * 단일 룸 루프 스레드에서만 위치/의도를 변경하므로 필드 동기화는 하지 않는다.
 * (입력 수신 스레드 → 루프 스레드 간에는 volatile로 최신값 전달)
 */
public class Player {

    private static final double SPAWN_Y = 0.5; // 캡슐 반높이(프론트와 일치)

    final String id;
    final String nick;

    // 위치/회전: 루프 스레드만 쓴다.
    double x;
    double y = SPAWN_Y;
    double z;
    double rotationY;

    Role role = Role.HIDER;

    // 입력(다른 스레드에서 갱신) → 루프 스레드에서 읽음. 최신값만 유지.
    private volatile double moveX;
    private volatile double moveZ;
    private volatile double desiredRotationY;
    private volatile long lastInputAtMs;
    private volatile long lastSeq = -1;

    Player(String id, String nick, double x, double z) {
        this.id = id;
        this.nick = nick;
        this.x = x;
        this.z = z;
    }

    /** 입력 수신 스레드에서 호출. 오래된(seq 역전) 입력은 버린다. */
    void applyInput(double mx, double mz, double rotationY, long seq, long nowMs) {
        if (seq <= lastSeq) {
            return;
        }
        lastSeq = seq;
        this.moveX = mx;
        this.moveZ = mz;
        this.desiredRotationY = rotationY;
        this.lastInputAtMs = nowMs;
    }

    /** 루프 스레드: 입력 타임아웃이 지났으면 정지 방향(0,0)으로 간주. */
    double inputMoveX(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) > timeoutMs ? 0 : moveX;
    }

    double inputMoveZ(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) > timeoutMs ? 0 : moveZ;
    }

    double desiredRotationY() {
        return desiredRotationY;
    }

    /** 매 tick 실리는 경량 상태. 위치는 2자리, 회전은 3자리로 반올림해 페이로드를 줄인다. */
    PlayerTick tickState() {
        return new PlayerTick(id, round(x, 100.0), round(z, 100.0), round(rotationY, 1000.0));
    }

    /** 로스터 변경(입·퇴장) 시에만 실리는 정적 정보. */
    RosterEntry rosterEntry() {
        return new RosterEntry(id, nick);
    }

    private static double round(double v, double factor) {
        return Math.round(v * factor) / factor;
    }
}
