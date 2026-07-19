package com.game3d.server.game;

import com.game3d.server.dto.PlayerTick;
import com.game3d.server.dto.RosterEntry;
import com.game3d.server.dto.VoteEntry;
import com.game3d.server.dto.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 하나의 게임 룸. 플레이어 hot state를 메모리에 격리 보관하고, tick마다 위치를 확정한다.
 * 입력(join/input)은 여러 스레드에서, tick()은 루프 스레드 한 곳에서 호출된다.
 */
public class Room {

    private static final Logger log = LoggerFactory.getLogger(Room.class);

    /** AI 봇의 고정 id. 스냅샷엔 일반 원격 플레이어처럼 실린다. */
    private static final String BOT_ID = "bot-1";

    /**
     * 봇이 쓸 사람 같은 닉네임 후보.
     *
     * 예전엔 닉이 그냥 "AI"였다. 마지막 단계가 <b>AI 지목 투표</b>라 그러면 정답이 화면에 적혀
     * 있는 셈이라, 사람 이름 중 하나를 골라 쓴다. 로스터의 bot 플래그도 결말 전까지 숨긴다
     * ({@link #snapshot}) — 둘 중 하나만 가려서는 정체가 그대로 드러난다.
     */
    private static final String[] BOT_NICKS = {
        "민준", "서연", "도윤", "하은", "지호", "수아", "예준", "지우"
    };

    /** 봇이 감방문을 여는 사거리(m). 프론트 prisonLayout.DOOR_RANGE 와 같은 값. */
    private static final double BOT_DOOR_RANGE = 3.0;

    /** 복도 절반 폭(m). 프론트 prisonLayout.CORRIDOR_HALF_Z 와 같은 값. */
    private static final double CORRIDOR_HALF_Z = 2.5;

    /**
     * 첫 사람이 감방을 연 뒤 봇이 자기 방을 열기까지 기다리는 시간(ms).
     *
     * 봇이 먼저 나오면 사람이 아직 퍼즐과 씨름하는 동안 AI가 앞서가는 그림이 된다. 사람이 하나
     * 나온 뒤에, 그것도 곧바로 말고 조금 뒤에 따라 나와야 "옆방도 푸는 중이었구나"로 읽힌다.
     */
    private static final long BOT_FOLLOW_DELAY_MS = 10_000;

    /**
     * 감방 자물쇠 id → 풀면 열리는 감방문 id. 프론트 interactables.ts의 lockbox.opensDoor 와 같다.
     *
     * 문을 여는 유일한 경로가 여기다. 클라가 보내는 /door 요청에 기대지 않는 이유:
     *   - 봇은 클라가 없어 /door를 못 보낸다(그러면 봇은 자기 방에 갇힌다)
     *   - /door를 그대로 믿으면 퍼즐을 안 풀고 문만 열어달라고 쏘는 게 가능하다
     */
    private static final Map<String, String> LOCK_OPENS = Map.of(
        "lock-A", "cell-A",
        "lock-B", "cell-B",
        "lock-C", "cell-C",
        "lock-D", "cell-D"
    );

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

    // 첫 감방문이 열린 시각(ms). 0이면 아직 아무도 못 나왔다. 봇 탈출 지연의 기준점.
    // 컨트롤러 스레드(사람 solve)에서 쓰고 루프 스레드(tick)에서 읽는다.
    private volatile long firstCellOpenedAtMs;

    // AI 지목 투표(투표자 → 지목 대상). 다시 찍으면 덮어쓴다.
    private final Map<String, String> votes = new ConcurrentHashMap<>();
    private final AtomicBoolean votesDirty = new AtomicBoolean();

    // 입장 순서. players는 ConcurrentHashMap이라 순회 순서가 입장 순이 아니다.
    // 방장을 "먼저 들어온 사람"으로 뽑으려면 순서를 따로 들고 있어야 한다.
    private final List<String> joinOrder = new CopyOnWriteArrayList<>();

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
            double[] s = randomCellSpawn();
            joinOrder.add(key); // 방장 선출용 순서. computeIfAbsent 안이라 최초 1회만 탄다.
            return new Player(key, nick, s[0], s[1]);
        });
        spawnBot();
        // 봇까지 넣은 뒤에 세워야 로스터에 봇 닉("AI")이 함께 실린다.
        rosterDirty.set(true); // 다음 스냅샷에 로스터 1회 재전송
        phaseDirty.set(true);  // 중간 입장자에게 현재 단계·남은 시간 1회 전송
    }

    /**
     * AI 봇 투입(멱등). 첫 사람이 들어오면 자동으로 같이 스폰된다.
     *
     * 사람과 같은 랜덤 감방 스폰을 쓴다. 예전엔 (0,0) 하드코딩이었는데, 감옥 재구성으로
     * 사람만 감방에 갇혀 시작하게 바뀌면서 봇 혼자 중앙 복도에 서 있는 상태가 됐다.
     */
    public void spawnBot() {
        players.computeIfAbsent(BOT_ID, key -> {
            double[] s = randomCellSpawn();
            joinOrder.add(key); // 봇도 순서에 넣되, 방장 선출에서는 제외된다(아래 snapshot 주석)
            return new Player(key, botNick(), s[0], s[1], new BotBrain(llm, planIntervalMs));
        });
    }

    /** 사람과 겹치지 않는 봇 닉을 고른다. 다 겹치면 그냥 무작위(그럴 일은 사실상 없다). */
    private String botNick() {
        Set<String> taken = new HashSet<>();
        for (Player p : players.values()) {
            taken.add(p.nick);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int start = rnd.nextInt(BOT_NICKS.length);
        for (int i = 0; i < BOT_NICKS.length; i++) {
            String candidate = BOT_NICKS[(start + i) % BOT_NICKS.length];
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return BOT_NICKS[start];
    }

    /** AI 지목 투표. 다시 찍으면 덮어쓴다. 자기 자신은 못 찍는다. */
    public void castVote(String voterId, String targetId) {
        if (voterId == null || targetId == null || voterId.equals(targetId)
                || !players.containsKey(targetId)) {
            return;
        }
        if (!targetId.equals(votes.put(voterId, targetId))) {
            votesDirty.set(true);
        }
    }

    /** 랜덤 감방 + 감방 내부 랜덤 위치. {x, z} */
    private static double[] randomCellSpawn() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double[] c = CELL_CENTERS[rnd.nextInt(CELL_CENTERS.length)];
        return new double[] {
            c[0] + rnd.nextDouble(-2.5, 2.5),
            c[1] + rnd.nextDouble(-2.5, 2.5),
        };
    }

    /** 이탈. */
    public void leave(String id) {
        if (players.remove(id) != null) {
            joinOrder.remove(id);
            votes.remove(id);   // 나간 사람의 표는 무효
            votes.values().removeIf(id::equals); // 나간 사람을 지목한 표도 무효
            votesDirty.set(true);
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
            // 결말에 들어서면 최종 집계를 한 번 실어 보낸다(그 전 표가 그대로면 dirty가 안 서 있다).
            if (phases.phase() == GamePhase.ENDED) {
                votesDirty.set(true);
            }
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
                double[] mv = p.brain.steer(p, players.values(), solvedIds, unreachableFor(p), nowMs);
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
                // 자물쇠 앞에서 머무는 시간을 다 채웠고, 첫 사람이 나간 지 충분히 지났으면
                // 이제 푼 것으로 친다. markSolved가 그 방 문까지 열어 주므로 봇은 곧 나갈 수 있다.
                // 아무도 못 나왔으면(0) 봇은 자물쇠 앞에서 계속 기다린다 — 푸는 중처럼 보인다.
                long notBefore = firstCellOpenedAtMs == 0
                        ? Long.MAX_VALUE
                        : firstCellOpenedAtMs + BOT_FOLLOW_DELAY_MS;
                String done = p.brain.pollSolved(nowMs, notBefore);
                if (done != null) {
                    log.info("방 {} 봇이 {} 해제", roomId, done);
                    markSolved(done);
                }

                // 봇이 근접만으로 문을 여는 길은 막아 뒀다. 예전엔 조건 없이 열었는데, 자물쇠가
                // 문에서 1.1m라 봇이 지나가며 남의 방까지 다 열어 퍼즐이 통째로 무의미해진다.
                // 자기 방은 위 markSolved로 열리니 이 경로는 잠기지 않은 문에만 필요하다.
                String door = Collision.nearestClosedDoor(p.x, p.z, openDoors, BOT_DOOR_RANGE);
                if (door != null && !LOCK_OPENS.containsValue(door) && openDoors.add(door)) {
                    log.info("방 {} 봇이 감방문 {} 열었다", roomId, door);
                }

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

    /**
     * 퍼즐 해결 기록(협동). 방 생명주기 동안 유지된다.
     * 감방 자물쇠였다면 그 방 문도 함께 연다 — 사람·봇이 같은 경로를 타게 하려는 것이다.
     */
    public void markSolved(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return;
        }
        solvedIds.add(objectId);
        String door = LOCK_OPENS.get(objectId);
        if (door != null && openDoors.add(door)) {
            log.info("방 {} 자물쇠 {} 해제 → 감방문 {} 열림", roomId, objectId, door);
            // 봇의 탈출 타이머는 첫 감방이 열린 시각부터 센다. 봇은 이 시각이 찍히기 전엔
            // 자기 자물쇠를 풀지 않으므로(tick의 pollSolved 참조) 여기 찍히는 건 항상 사람이다.
            if (firstCellOpenedAtMs == 0) {
                firstCellOpenedAtMs = System.currentTimeMillis();
            }
        }
    }

    /**
     * 감방문 열림 토글(협동). F 요청마다 열림↔닫힘. 열린 문은 충돌에서 제외된다.
     *
     * ⚠️ 자물쇠가 관리하는 감방문(LOCK_OPENS)은 여기서 건드리지 않는다. 그 문의 상태는
     * {@link #markSolved}가 단독으로 정한다. 프론트는 퍼즐을 풀 때 solve에 이어 door도 보내는데,
     * 그걸 토글로 받으면 <b>방금 연 문을 곧바로 도로 닫는다</b>(2026-07-19에 실제로 그랬다).
     * 겸사겸사 퍼즐을 건너뛰고 door만 쏘아 여는 길도 막힌다.
     */
    public void toggleDoor(String doorId) {
        if (doorId == null || doorId.isBlank() || LOCK_OPENS.containsValue(doorId)) {
            return;
        }
        if (!openDoors.remove(doorId)) {
            openDoors.add(doorId);
        }
    }

    /**
     * 이 봇이 지금 갈 수 없는 대상의 id — <b>POI와 사람을 함께</b> 담는다(둘 다 goal의 targetId라서).
     *
     * 안 풀린 감방 자물쇠는 항상 잠긴 문 뒤에 있고(그 문을 여는 게 바로 그 자물쇠라서), 그 방 안에
     * 있는 사람도 마찬가지로 못 닿는다. 같은 감방 안에 있지 않으면 둘 다 도달 불가다.
     *
     * 빼 주지 않으면 봇이 열 수 없는 문 앞에 붙어 멈춘다(2026-07-18 정지 회귀와 같은 모양).
     * 자물쇠만 걸렀다가 <b>잠긴 방 안의 사람을 따라가려다</b> 같은 증상이 재현된 적 있다(2026-07-19).
     */
    private Set<String> unreachableFor(Player self) {
        Set<String> out = null;
        for (String lockId : LOCK_OPENS.keySet()) {
            if (solvedIds.contains(lockId)) {
                continue; // 문이 열렸으니 그 방 안의 것은 모두 닿는다
            }
            Interactables.Poi lock = Interactables.find(lockId);
            if (lock == null || sameCell(self.x, self.z, lock.x(), lock.z())) {
                continue; // 내가 그 방 안이면 자물쇠도 쪽지도 사람도 닿는다
            }
            if (out == null) {
                out = new HashSet<>();
            }
            // 잠긴 방 안의 것 전부. 자물쇠만 빼고 쪽지를 남겨두면 LLM이 GOTO_NOTE로 그 방
            // 쪽지를 골라(운영 로그에서 실제로 그런다) 봇이 남의 감방문 앞에 박힌다.
            for (Interactables.Poi poi : Interactables.all()) {
                if (sameCell(poi.x(), poi.z(), lock.x(), lock.z())) {
                    out.add(poi.id());
                }
            }
            for (Player other : players.values()) {
                if (!other.bot && sameCell(other.x, other.z, lock.x(), lock.z())) {
                    out.add(other.id);
                }
            }
        }
        return out == null ? Set.of() : out;
    }

    /**
     * 두 점이 같은 감방인지. 감방은 감방블록(|x|&lt;14, |z|&lt;11)을 복도(|z|&le;2.5)로 가른
     * 네 사분면이다. 복도·통로에 서 있으면 어느 감방에도 속하지 않는다.
     */
    private static boolean sameCell(double ax, double az, double bx, double bz) {
        return Math.abs(ax) < 14 && Math.abs(az) < 11 && Math.abs(az) > CORRIDOR_HALF_Z
                && (ax < 0) == (bx < 0) && (az < 0) == (bz < 0);
    }

    public WorldSnapshot snapshot(long nowMs) {
        List<PlayerTick> states = new ArrayList<>(players.size());
        for (Player p : players.values()) {
            states.add(p.tickState());
        }
        boolean ended = phases.phase() == GamePhase.ENDED;

        // 로스터는 변경됐을 때만 싣는다(그 외 null → JSON 생략). 입장 순서대로 담는다 —
        // 클라가 첫 번째를 방장으로 뽑는데, players는 ConcurrentHashMap이라 순회 순서가 제멋대로다.
        //
        // ⚠️ bot 플래그는 항상 false로 내보낸다. 마지막이 AI 지목 투표라 정체를 알려주면 게임이
        //    성립하지 않는다. 진짜 정체는 결말(ENDED)에 aiId로만 나간다.
        List<RosterEntry> roster = null;
        if (rosterDirty.getAndSet(false)) {
            roster = new ArrayList<>(players.size());
            for (String id : joinOrder) {
                Player p = players.get(id);
                if (p != null) {
                    roster.add(new RosterEntry(p.id, p.nick, false));
                }
            }
        }
        // 단계도 같은 규약. 클라는 remain을 받은 시점부터 스스로 카운트다운한다.
        //
        // aiId도 여기 얹는다. 결말이라고 매 tick 실으면 20Hz 내내 따라붙는데, 한 번만 알면
        // 되는 값이다. 단계는 전환 시와 입장 시에 나가므로 중간 입장자도 함께 받는다.
        String phase = null;
        Long phaseRemainMs = null;
        String aiId = null;
        if (phaseDirty.getAndSet(false)) {
            phase = phases.phase().name();
            phaseRemainMs = phases.remainMs(nowMs);
            if (ended) {
                aiId = BOT_ID;
            }
        }
        // 표도 바뀔 때만. 결말 진입 시엔 tick이 votesDirty를 세워 최종 집계가 함께 나간다.
        List<VoteEntry> voteList = null;
        if (votesDirty.getAndSet(false)) {
            voteList = new ArrayList<>(votes.size());
            for (Map.Entry<String, String> e : votes.entrySet()) {
                voteList.add(new VoteEntry(e.getKey(), e.getValue()));
            }
        }
        return new WorldSnapshot(tick, states, roster, new ArrayList<>(solvedIds),
                new ArrayList<>(openDoors), phase, phaseRemainMs, voteList, aiId);
    }
}
