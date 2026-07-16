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

    private final String roomId;
    private final GameProperties props;
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    // 해결된 퍼즐 오브젝트 id(협동 동기화). 컨트롤러 스레드에서 추가, 루프 스레드에서 스냅샷 읽기.
    private final Set<String> solvedIds = ConcurrentHashMap.newKeySet();
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

    /** 이동 의도 반영(권위 서버: 좌표가 아닌 방향만 받는다). */
    public void input(String id, double moveX, double moveZ, double rotationY, long seq, long nowMs) {
        Player p = players.get(id);
        if (p != null) {
            p.applyInput(moveX, moveZ, rotationY, seq, nowMs);
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
            if (p.bot) {
                // 봇: STOMP 입력 대신 브레인이 이동 의도를 만든다. 이하 이동·충돌은 사람과 공유.
                double[] mv = p.brain.steer(p, players.values(), solvedIds, nowMs);
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
        return new WorldSnapshot(tick, states, roster, new ArrayList<>(solvedIds), phase, phaseRemainMs);
    }
}
