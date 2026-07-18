package com.game3d.server.game;

import com.game3d.server.dto.PlayerTick;
import com.game3d.server.dto.RosterEntry;
import com.game3d.server.dto.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 하나의 게임 룸. 플레이어 hot state를 메모리에 격리 보관하고, tick마다 위치를 확정한다.
 * 입력(join/input)은 여러 스레드에서, tick()은 루프 스레드 한 곳에서 호출된다.
 */
public class Room {

    private static final Logger log = LoggerFactory.getLogger(Room.class);

    /** AI 봇의 고정 id/닉. 스냅샷엔 일반 원격 플레이어처럼 실린다(프론트 무변경). */
    private static final String BOT_ID = "bot-1";
    private static final String BOT_NICK = "AI";

    /** 감방 4개의 중심(스폰 기준). 프론트 prisonLayout.CELLS 와 일치. */
    private static final double[][] CELL_CENTERS = {
        {-7, 6.5},  // 1호실(A)
        {7, 6.5},   // 2호실(B)
        {-7, -6.5}, // 3호실(C)
        {7, -6.5},  // 4호실(D)
    };

    private final String roomId;
    private final GameProperties props;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    // 해결된 퍼즐 오브젝트 id(협동 동기화). 컨트롤러 스레드에서 추가, 루프 스레드에서 스냅샷 읽기.
    private final Set<String> solvedIds = ConcurrentHashMap.newKeySet();
    // 열린 감방문 id(F 토글). 컨트롤러 스레드에서 토글, 루프 스레드에서 충돌·스냅샷 읽기.
    private final Set<String> openDoors = ConcurrentHashMap.newKeySet();
    // 로스터(id·nick) 변경 여부. 입·퇴장 스레드에서 set, 루프 스레드에서 getAndSet.
    // 초기 true → 첫 스냅샷에 로스터를 한 번 실어 클라가 닉네임을 받게 한다.
    private final AtomicBoolean rosterDirty = new AtomicBoolean(true);
    // 진행 단계 변경 여부. 로스터와 같은 규약 — 바뀔 때만 싣는다.
    // 입장 때도 세운다: 중간에 들어온 사람도 현재 단계를 받아야 한다.
    private final AtomicBoolean phaseDirty = new AtomicBoolean(true);
    private long tick;

    /** 진행 단계 시계. 루프 스레드(tick)에서만 만진다. */
    private final PhaseTimeline phases;

    /** 봇 브레인의 LLM 느린 층. null이면 스크립트로만 돈다. */
    private final BotPlanner llm;
    private final long planIntervalMs;

    Room(String roomId, GameProperties props, PhaseProperties phaseProps, BotPlanner llm, long planIntervalMs) {
        this.roomId = roomId;
        this.props = props;
        this.phases = new PhaseTimeline(phaseProps);
        this.llm = llm;
        this.planIntervalMs = planIntervalMs;
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

    /** 입장(멱등). 이미 있으면 닉네임만 갱신. 랜덤 감방 + 감방 내부 랜덤 위치에 스폰. */
    public void join(String id, String nick) {
        players.computeIfAbsent(id, key -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            double[] c = CELL_CENTERS[rnd.nextInt(CELL_CENTERS.length)];
            double sx = c[0] + rnd.nextDouble(-2.5, 2.5);
            double sz = c[1] + rnd.nextDouble(-2.5, 2.5);
            return new Player(key, nick, sx, sz);
        });
        spawnBot();
        // 봇까지 넣은 뒤에 세워야 로스터에 봇 닉("AI")이 함께 실린다.
        rosterDirty.set(true); // 다음 스냅샷에 로스터 1회 재전송
        phaseDirty.set(true);  // 중간 입장자에게 현재 단계·남은 시간 1회 전송
    }

    /** AI 봇 투입(멱등). 첫 사람이 들어오면 자동으로 같이 스폰된다. */
    public void spawnBot() {
        players.computeIfAbsent(BOT_ID, key -> new Player(key, BOT_NICK, 0, 0, new BotBrain(llm, planIntervalMs)));
    }

    /** 이탈. */
    public void leave(String id) {
        if (players.remove(id) != null) {
            rosterDirty.set(true);
        }
    }

    /** 이동 의도 반영(권위 서버: 좌표가 아닌 방향만 받는다). sprint/jump도 의도일 뿐 판정은 tick이 한다. */
    public void input(String id, double moveX, double moveZ, double rotationY,
                      boolean sprint, boolean jump, long seq, long nowMs) {
        Player p = players.get(id);
        if (p != null) {
            p.applyInput(moveX, moveZ, rotationY, sprint, jump, seq, nowMs);
        }
    }

    /** 한 tick 진행: 진행 단계를 갱신하고, 각 플레이어의 이동 의도를 검증·적용한다. */
    public void tick(long nowMs) {
        // 단계 전이는 경과 시간으로만 결정된다(PhaseTimeline). 바뀐 tick에만 스냅샷에 싣는다.
        if (phases.advance(nowMs)) {
            phaseDirty.set(true);
            log.info("방 {} 단계 전환 → {}", roomId, phases.phase());
        }

        double dt = props.tickSeconds();
        double speed = props.speed();
        long timeout = props.inputTimeoutMs();

        for (Player p : players.values()) {
            double mx;
            double mz;
            boolean sprint = false;
            if (p.bot) {
                // 봇: STOMP 입력 대신 브레인이 이동 의도를 만든다. 이하 이동·충돌은 사람과 공유.
                // 봇은 아직 달리기/점프를 쓰지 않는다(브레인이 2D 방향만 낸다).
                double[] mv = p.brain.steer(p, players.values(), solvedIds, nowMs);
                mx = mv[0];
                mz = mv[1];
            } else {
                mx = p.inputMoveX(nowMs, timeout);
                mz = p.inputMoveZ(nowMs, timeout);
                sprint = p.inputSprint(nowMs, timeout);
            }

            // 클라 입력 불신: 이동 벡터 크기를 1로 클램프(속도 핵 방지).
            double len = Math.hypot(mx, mz);
            if (len > 1.0) {
                mx /= len;
                mz /= len;
            }

            // 달리기 배수도 서버가 곱한다. 클라가 sprint를 위조해도 배수 자체는 서버 설정이 상한이다.
            double moveSpeed = sprint ? speed * props.sprintMultiplier() : speed;
            p.x += mx * moveSpeed * dt;
            p.z += mz * moveSpeed * dt;

            // 벽/장애물 충돌 해석(열린 감방문은 통과). 프론트 예측과 동일 로직.
            // 충돌은 2D(x/z)다 — 점프해도 장애물을 넘지 못한다. 넘게 하려면 Collision을 3D로
            // 바꿔야 하고 프론트 collision.ts도 같이 고쳐야 한다(이중 관리).
            double[] r = Collision.resolve(p.x, p.z, openDoors);
            p.x = r[0];
            p.z = r[1];

            // 수직: 접지 중 점프 의도가 있으면 발사, 그 뒤엔 중력으로 적분. 착지하면 딱 지면에 고정.
            // 누르고 있으면 착지 즉시 다시 뛴다(연속 점프) — 별도 엣지 판정은 두지 않았다.
            if (!p.bot) {
                if (p.grounded() && p.inputJump(nowMs, timeout)) {
                    p.vy = props.jumpSpeed();
                }
                if (!p.grounded() || p.vy > 0) {
                    p.vy -= props.gravity() * dt;
                    p.y += p.vy * dt;
                    if (p.y <= Player.GROUND_Y) {
                        p.y = Player.GROUND_Y;
                        p.vy = 0;
                    }
                }
            }

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

    /** 감방문 열림 토글(협동). F 요청마다 열림↔닫힘. 열린 문은 충돌에서 제외된다. */
    public void toggleDoor(String doorId) {
        if (doorId == null || doorId.isBlank()) {
            return;
        }
        if (!openDoors.remove(doorId)) {
            openDoors.add(doorId);
        }
    }

    public WorldSnapshot snapshot(long nowMs) {
        List<PlayerTick> states = new ArrayList<>(players.size());
        for (Player p : players.values()) {
            states.add(p.tickState());
        }
        // 로스터는 변경됐을 때만 싣는다(그 외 null → JSON 생략).
        List<RosterEntry> roster = null;
        if (rosterDirty.getAndSet(false)) {
            roster = new ArrayList<>(players.size());
            for (Player p : players.values()) {
                roster.add(p.rosterEntry());
            }
        }
        // 단계도 같은 규약. 클라는 remain을 받은 시점부터 스스로 카운트다운한다.
        String phase = null;
        Long phaseRemainMs = null;
        if (phaseDirty.getAndSet(false)) {
            phase = phases.phase().name();
            phaseRemainMs = phases.remainMs(nowMs);
        }
        return new WorldSnapshot(tick, states, roster, new ArrayList<>(solvedIds),
                new ArrayList<>(openDoors), phase, phaseRemainMs);
    }
}
