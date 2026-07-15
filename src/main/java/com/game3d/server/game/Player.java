package com.game3d.server.game;

import com.game3d.server.dto.PlayerState;
import com.game3d.server.dto.Role;
import com.game3d.server.dto.Vec3;

/**
 * 룸 안의 가변(hot) 플레이어 상태. DB가 아닌 메모리에 둔다.
 * 단일 룸 루프 스레드에서만 위치/의도를 변경하므로 필드 동기화는 하지 않는다.
 * (입력 수신 스레드 → 루프 스레드 간에는 volatile로 최신값 전달)
 */
public class Player {

    private static final double SPAWN_Y = 0.5; // 캡슐 반높이(프론트와 일치)

    final String id;
    final String nick;

    /** AI 봇 여부. 봇은 STOMP 입력 대신 브레인이 이동 의도를 채운다. */
    final boolean bot;

    /** 봇일 때만 존재(사람은 null). */
    final BotBrain brain;

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

    /** 사람. */
    Player(String id, String nick, double x, double z) {
        this(id, nick, x, z, null);
    }

    /** brain이 있으면 봇, null이면 사람. 브레인은 룸이 만들어 넘긴다(플래너 주입 때문). */
    Player(String id, String nick, double x, double z, BotBrain brain) {
        this.id = id;
        this.nick = nick;
        this.x = x;
        this.z = z;
        this.bot = brain != null;
        this.brain = brain;
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

    PlayerState snapshot() {
        return new PlayerState(id, nick, new Vec3(x, y, z), rotationY, role);
    }
}
