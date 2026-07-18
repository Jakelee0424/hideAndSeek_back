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

    /** 접지 상태의 y(캡슐 반높이). 점프는 이 값을 바닥으로 삼는다. */
    static final double GROUND_Y = 0.5;

    final String id;
    final String nick;

    /** AI 봇 여부. 봇은 STOMP 입력 대신 브레인이 이동 의도를 채운다. */
    final boolean bot;

    /** 봇일 때만 존재(사람은 null). */
    final BotBrain brain;

    // 위치/회전: 루프 스레드만 쓴다.
    double x;
    double y = GROUND_Y;
    double z;
    double rotationY;

    /** 수직 속도(m/s). 루프 스레드만 쓴다. 접지 중엔 0. */
    double vy;

    Role role = Role.HIDER;

    // 입력(다른 스레드에서 갱신) → 루프 스레드에서 읽음. 최신값만 유지.
    private volatile double moveX;
    private volatile double moveZ;
    private volatile double desiredRotationY;
    private volatile boolean sprint;
    private volatile boolean jump;
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
    void applyInput(double mx, double mz, double rotationY, boolean sprint, boolean jump,
                    long seq, long nowMs) {
        if (seq <= lastSeq) {
            return;
        }
        lastSeq = seq;
        this.moveX = mx;
        this.moveZ = mz;
        this.desiredRotationY = rotationY;
        this.sprint = sprint;
        this.jump = jump;
        this.lastInputAtMs = nowMs;
    }

    /** 루프 스레드: 입력 타임아웃이 지났으면 정지 방향(0,0)으로 간주. */
    double inputMoveX(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) > timeoutMs ? 0 : moveX;
    }

    double inputMoveZ(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) > timeoutMs ? 0 : moveZ;
    }

    /** 연결이 끊기거나 창이 백그라운드로 가면 달리기가 켜진 채 굳지 않도록 타임아웃을 함께 본다. */
    boolean inputSprint(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) <= timeoutMs && sprint;
    }

    boolean inputJump(long nowMs, long timeoutMs) {
        return (nowMs - lastInputAtMs) <= timeoutMs && jump;
    }

    boolean grounded() {
        return y <= GROUND_Y + 1e-6;
    }

    double desiredRotationY() {
        return desiredRotationY;
    }

    /**
     * 매 tick 실리는 경량 상태. 위치는 2자리, 회전은 3자리로 반올림해 페이로드를 줄인다.
     * y는 절대 좌표가 아니라 **지면 위 높이**로 보낸다(프론트의 발바닥 y=0 규약).
     */
    PlayerTick tickState() {
        return new PlayerTick(id, round(x, 100.0), round(y - GROUND_Y, 100.0),
                round(z, 100.0), round(rotationY, 1000.0));
    }

    /** 로스터 변경(입·퇴장) 시에만 실리는 정적 정보. */
    RosterEntry rosterEntry() {
        return new RosterEntry(id, nick, bot);
    }

    private static double round(double v, double factor) {
        return Math.round(v * factor) / factor;
    }
}
