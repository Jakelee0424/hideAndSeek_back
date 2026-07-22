package com.game3d.server.game;

import com.game3d.server.dto.ChatEvent;
import com.game3d.server.dto.PlayerTick;
import com.game3d.server.dto.PunchEvent;
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
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /** 최종 탈옥문 id. 이게 풀리면 팀 전체의 탈출이 끝난 것으로 본다(협동 오브젝트라 1회). */
    private static final String ESCAPE_GATE_ID = "escape-gate";

    /**
     * 탈옥문 해제 뒤 색출(VOTE)로 넘어가기까지 기다리는 시간(ms).
     *
     * 프론트 EscapeOverlay의 HOLD_MS(5.2s)와 맞춘다 — 탈출 팡파르가 걷힌 직후 자연스럽게
     * 이어지게. 이 지연이 없으면 연출과 투표 화면이 겹쳐 뜬다.
     */
    private static final long ESCAPE_TO_VOTE_DELAY_MS = 5_200;

    /**
     * 봇 죄수번호의 범위. 사람은 프론트 Lobby.randomPrisonerNick()이 같은 규칙으로 딴다.
     *
     * 예전엔 닉이 그냥 "AI"였다가, 그다음엔 사람 이름("민준"·"서연"…)이었다. 지금은 사람도
     * 죄수번호를 쓰므로 봇도 같은 형식이어야 한다 — 마지막 단계가 <b>AI 지목 투표</b>라
     * 혼자 다른 모양의 이름을 달면 정답이 화면에 적혀 있는 셈이다. 로스터의 bot 플래그도
     * 결말 전까지 숨긴다({@link #snapshot}) — 둘 중 하나만 가려서는 정체가 그대로 드러난다.
     *
     * ⚠️ 형식을 바꾸면 프론트 Lobby와 반드시 함께 고칠 것.
     */
    private static final int BOT_NICK_MIN = 1000;
    private static final int BOT_NICK_MAX = 9999;

    /** 봇이 감방문을 여는 사거리(m). 프론트 prisonLayout.DOOR_RANGE 와 같은 값. */
    private static final double BOT_DOOR_RANGE = 3.0;

    /** 감방 내부 반경(m). 스폰·같은-감방 판정에 쓴다. 프론트 감방 rect(16×8)의 절반. */
    private static final double CELL_HALF_X = 8;
    private static final double CELL_HALF_Z = 4;

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
        "lock-D", "cell-D",
        // 별관 복도 옆방(프론트 interactables.ts). 풀면 그 방 문이 열린다.
        "lock-work", "door-work",       // 작업장
        "lock-med", "door-med",         // 의무실
        "lock-laundry", "door-laundry", // 세탁실
        // 최종 탈옥문 → 남벽의 파란 정문. 클리어 연출이자, 봇·/door 요청이 정문을
        // 함부로 열지 못하게 막는 잠금(containsValue 검사)이기도 하다.
        "escape-gate", "gate-main"
    );

    // ── 펀치(약한 넉백) ────────────────────────────────────────────────────────
    // 아래 넷 중 KNOCKBACK_SPEED/KNOCKBACK_TAU는 프론트 punchConfig.ts와 이중 관리다. victim <b>본인</b>
    // 클라가 자기 예측 위치에 같은 힘을 재현하므로(결정론적 복제), 어긋나면 맞은 사람 화면만 서버와
    // 벌어진다. COOLDOWN/RANGE/CONE은 판정용이라 서버 단독이다(프론트 쿨다운은 연출용 별도값).

    /** 펀치 쿨다운(ms). 연타로 상대를 계속 밀어내지 못하게 한다. */
    private static final long PUNCH_COOLDOWN_MS = 600;
    /** 펀치가 닿는 거리(m). */
    private static final double PUNCH_RANGE = 2.2;
    /** 전방 판정. 바라보는 방향과 대상 방향의 코사인이 이 값 이상이어야 맞는다(≈ ±69° 콘). */
    private static final double PUNCH_CONE_DOT = 0.35;
    /** 넉백 초기 속도(m/s). 약하게 — 총 밀림 = SPEED*TAU ≈ 0.6m. */
    static final double KNOCKBACK_SPEED = 5.0;
    /** 넉백 감쇠 시간상수(s). 매 tick v *= exp(-dt/TAU). 약 3*TAU(≈0.36s)면 사실상 멈춘다. */
    static final double KNOCKBACK_TAU = 0.12;

    /** 채팅 한 줄의 최대 길이(자). 넘으면 자른다 — 거부하면 길게 쓴 사람은 이유도 모르고 말이 사라진다. */
    private static final int CHAT_MAX_LEN = 120;
    /** 채팅 최소 간격(ms). 도배 방지. 이 안에 또 보내면 조용히 버린다. */
    private static final long CHAT_MIN_INTERVAL_MS = 700;
    /**
     * 한 tick에 내보낼 채팅 상한. 큐가 폭주해도 브로드캐스트가 tick을 잡아먹지 않게 한다
     * (남은 건 다음 tick에 나간다 — 50ms 뒤라 사람 눈에는 차이가 없다).
     */
    private static final int CHAT_DRAIN_PER_TICK = 20;

    /** 감방 4개의 중심(스폰 기준). 프론트 prisonLayout.CELLS(수감동, 2:2 마주보기)와 일치. */
    private static final double[][] CELL_CENTERS = {
        {-30, 24}, // 감방 1-1 (북측)
        {-14, 24}, // 감방 1-2 (북측)
        {-30, 10}, // 감방 1-3 (남측)
        {-14, 10}, // 감방 1-4 (남측)
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

    // 정기 순찰. 시계와 같은 규약으로 루프 스레드가 굴리고, 바뀔 때만 스냅샷에 싣는다.
    // 입장 때도 세운다: 순찰 도중에 들어온 사람도 지금 순찰 중임을 알아야 한다.
    private final Patrol patrol;
    private final PatrolProperties patrolProps;
    private final AtomicBoolean patrolDirty = new AtomicBoolean(true);

    private long tick;

    // 이 tick에 성사된 펀치들. tick()이 채우고 곧이어 snapshot()이 실어 보낸 뒤 비운다.
    // 둘 다 루프 스레드에서 순차 호출되므로 동기화가 필요 없다(GameLoop: tick → snapshot).
    private List<PunchEvent> pendingPunches;

    // 아직 내보내지 않은 채팅. GameLoop이 tick 뒤에 비우며 /topic/rooms/{id}/chat 으로 보낸다.
    //
    // pendingPunches와 달리 동시성 큐인 이유: 펀치는 루프 스레드가 만들고 루프 스레드가 싣지만,
    // 채팅은 STOMP 인바운드 풀(사람)과 루프 스레드(봇)가 함께 넣고 루프 스레드가 뺀다.
    private final ConcurrentLinkedQueue<ChatEvent> pendingChat = new ConcurrentLinkedQueue<>();

    // 첫 감방문이 열린 시각(ms). 0이면 아직 아무도 못 나왔다. 봇 탈출 지연의 기준점.
    // 컨트롤러 스레드(사람 solve)에서 쓰고 루프 스레드(tick)에서 읽는다.
    private volatile long firstCellOpenedAtMs;

    // 탈옥문(escape-gate)이 풀린 시각(ms). 0이면 아직 안 열렸다. 색출(VOTE) 조기 전환의 기준점.
    // markSolved(컨트롤러 스레드 사람 solve / 루프 스레드 봇)에서 쓰고 tick(루프 스레드)에서 읽는다.
    private volatile long escapeCompletedAtMs;

    // AI 지목 투표(투표자 → 지목 대상). 다시 찍으면 덮어쓴다.
    private final Map<String, String> votes = new ConcurrentHashMap<>();
    private final AtomicBoolean votesDirty = new AtomicBoolean();

    // 준비 완료를 누른 사람들. 대기방에서만 의미가 있다.
    private final Set<String> ready = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean readyDirty = new AtomicBoolean();

    // 시작 요청. 컨트롤러 스레드가 세우고 루프 스레드(tick)가 소비한다 —
    // PhaseTimeline은 루프 스레드 전용이라 컨트롤러가 직접 시작시키면 규약이 깨진다.
    private volatile boolean startRequested;

    // 이미 배정된 감방 번호와 그 주인. 한 방에 둘이 몰리지 않게 하려는 것이다.
    private final Set<Integer> takenCells = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> cellOfPlayer = new ConcurrentHashMap<>();

    // 입장 순서. players는 ConcurrentHashMap이라 순회 순서가 입장 순이 아니다.
    // 방장을 "먼저 들어온 사람"으로 뽑으려면 순서를 따로 들고 있어야 한다.
    private final List<String> joinOrder = new CopyOnWriteArrayList<>();

    /** 진행 단계 시계. 루프 스레드(tick)에서만 만진다. */
    private final PhaseTimeline phases;

    /** 봇 브레인의 LLM 느린 층. null이면 스크립트로만 돈다. */
    private final BotPlanner llm;
    private final long planIntervalMs;

    /** 테스트 방이면 준비·시작을 기다리지 않고 첫 입장에서 바로 시작한다. */
    private final boolean autoStart;

    Room(String roomId, GameProperties props, PhaseProperties phaseProps,
         PatrolProperties patrolProps, BotPlanner llm, long planIntervalMs, boolean autoStart) {
        this.roomId = roomId;
        this.props = props;
        this.phases = new PhaseTimeline(phaseProps);
        // 순찰 시각은 방을 만들 때 다 뽑아 둔다(Patrol 주석 참고). 방마다 다른 시각이 나온다.
        this.patrol = new Patrol(patrolProps, phaseProps);
        this.patrolProps = patrolProps;
        this.llm = llm;
        this.planIntervalMs = planIntervalMs;
        this.autoStart = autoStart;
    }

    public String roomId() {
        return roomId;
    }

    /** 방에 있는 사람 수(AI 봇 제외). 정원 판정에 쓴다. */
    private int humanCount() {
        int n = 0;
        for (Player p : players.values()) {
            if (!p.bot) {
                n++;
            }
        }
        return n;
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

    /**
     * 입장(멱등). 이미 있으면 그대로 통과. 랜덤 감방 + 감방 내부 랜덤 위치에 스폰.
     *
     * @return 들어왔으면 true, 방이 가득 차 거절했으면 false. 거절 시 호출부는 대기열 자리를
     *         반납하고 세션을 묶지 않아야 한다 — 안 그러면 유령 자리가 남는다.
     */
    public synchronized boolean join(String id, String nick) {
        // synchronized인 이유: 세는 것과 넣는 것이 떨어져 있으면 정원 검사가 무의미해진다.
        // STOMP 인바운드는 스레드 풀이라 여러 명이 실제로 동시에 들어온다 — 그러면 전원이
        // "아직 0명"인 시점에 검사를 통과한다(정원 3에 5명이 들어간 실측이 있다).
        // 입장은 사람당 한 번뿐이라 직렬화 비용은 문제가 되지 않는다.

        // 이미 있는 사람의 재접속은 정원과 무관하게 받는다(새로고침으로 쫓겨나면 안 된다).
        if (!players.containsKey(id) && humanCount() >= props.maxPlayersPerRoom()) {
            log.info("방 {} 정원 초과({}명) → {} 입장 거절", roomId, props.maxPlayersPerRoom(), id);
            return false;
        }
        players.computeIfAbsent(id, key -> {
            double[] s = takeFreeCell(key);
            joinOrder.add(key); // 방장 선출용 순서. computeIfAbsent 안이라 최초 1회만 탄다.
            return new Player(key, nick, s[0], s[1]);
        });
        spawnBot();
        // 봇까지 넣은 뒤에 세워야 로스터에 봇 닉이 함께 실린다.
        rosterDirty.set(true); // 다음 스냅샷에 로스터 1회 재전송
        phaseDirty.set(true);  // 중간 입장자에게 현재 단계·남은 시간 1회 전송
        readyDirty.set(true);  // 새로 들어온 사람도 남들의 준비 상태를 봐야 한다
        patrolDirty.set(true); // 순찰 도중에 들어왔다면 지금 순찰 중임을 알아야 한다
        // 테스트 방은 준비·시작을 기다리지 않는다. 단계가 LOBBY를 벗어나면 프론트가 알아서
        // 게임 화면으로 넘겨주므로(대기방의 phase 감시), 클라는 손볼 게 없다.
        if (autoStart) {
            startRequested = true;
        }
        return true;
    }

    /**
     * AI 봇 투입(멱등). 첫 사람이 들어오면 자동으로 같이 스폰된다.
     *
     * 사람과 같은 랜덤 감방 스폰을 쓴다. 예전엔 (0,0) 하드코딩이었는데, 감옥 재구성으로
     * 사람만 감방에 갇혀 시작하게 바뀌면서 봇 혼자 중앙 복도에 서 있는 상태가 됐다.
     */
    public void spawnBot() {
        players.computeIfAbsent(BOT_ID, key -> {
            double[] s = takeFreeCell(key);
            joinOrder.add(key); // 봇도 순서에 넣되, 방장 선출에서는 제외된다(아래 snapshot 주석)
            // 봇은 준비 완료 상태로 들어온다. 서버의 allReady는 어차피 봇을 세지 않지만,
            // 클라는 결말 전까지 누가 봇인지 알 수 없어(roster.bot을 false로 숨긴다)
            // 봇을 사람으로 보고 "쟤가 아직 준비를 안 했다"며 시작 버튼을 막는다.
            // 준비된 것으로 실어 보내면 위장도 유지되고 클라 계산도 맞는다.
            ready.add(key);
            readyDirty.set(true);
            return new Player(key, botNick(), s[0], s[1],
                    new BotBrain(llm, planIntervalMs, this::botSay));
        });
    }

    /** 사람과 겹치지 않는 봇 죄수번호를 고른다. 다 겹치면 그냥 무작위(9000개 중이라 그럴 일은 없다). */
    private String botNick() {
        Set<String> taken = new HashSet<>();
        for (Player p : players.values()) {
            taken.add(p.nick);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String candidate = null;
        for (int i = 0; i < 32; i++) {
            candidate = "죄수 " + rnd.nextInt(BOT_NICK_MIN, BOT_NICK_MAX + 1);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return candidate;
    }

    /** 대기방 준비 토글. */
    public void setReady(String id, boolean value) {
        if (id == null || !players.containsKey(id)) {
            return;
        }
        if (value ? ready.add(id) : ready.remove(id)) {
            readyDirty.set(true);
        }
    }

    /** 사람이 한 명 이상이고 그 전원이 준비를 마쳤는가. 봇은 세지 않는다. */
    public boolean allReady() {
        int humans = 0;
        for (Player p : players.values()) {
            if (p.bot) {
                continue;
            }
            humans++;
            if (!ready.contains(p.id)) {
                return false;
            }
        }
        return humans > 0;
    }

    /**
     * 게임 시작 요청. 전원이 준비됐을 때만 받아들인다.
     * 실제 시작은 다음 tick이 한다(위 startRequested 주석 참고).
     *
     * @return 요청이 받아들여졌으면 true.
     */
    public boolean requestStart() {
        if (!allReady()) {
            return false;
        }
        startRequested = true;
        return true;
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

    /**
     * 아직 아무도 없는 감방을 하나 골라 그 안 임의 위치에 세운다. {x, z}
     *
     * 예전엔 그냥 무작위로 골랐는데, 감방 4개에 사람 3 + 봇 1을 무작위로 넣으면 한 방에
     * 둘이 몰리는 일이 흔하다(빈 방이 생기고, 그 방 쪽지는 아무도 못 본다).
     * 정원이 3명이라 봇을 합쳐도 4 이하 — 빈 방이 항상 있으므로 겹칠 일이 없다.
     *
     * join이 synchronized라 배정과 기록 사이에 끼어들 여지가 없다.
     */
    private double[] takeFreeCell(String playerId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int idx = -1;
        // 빈 방 중에서 무작위로 — 늘 1호실부터 차는 것보다 자연스럽다.
        int start = rnd.nextInt(CELL_CENTERS.length);
        for (int i = 0; i < CELL_CENTERS.length; i++) {
            int cand = (start + i) % CELL_CENTERS.length;
            if (!takenCells.contains(cand)) {
                idx = cand;
                break;
            }
        }
        if (idx < 0) {
            idx = start; // 정원을 넘겨 방이 다 찼다면 그때는 겹쳐도 어쩔 수 없다
        }
        takenCells.add(idx);
        cellOfPlayer.put(playerId, idx);

        double[] c = CELL_CENTERS[idx];
        // 감방 16×8 — 벽·침상에 끼지 않게 여유를 두고 뿌린다. 프론트 randomCellSpawn과 같은 범위.
        return new double[] {
            c[0] + rnd.nextDouble(-6, 6),
            c[1] + rnd.nextDouble(-2.5, 2.5),
        };
    }

    /** 이탈. */
    public void leave(String id) {
        if (players.remove(id) != null) {
            joinOrder.remove(id);
            votes.remove(id);   // 나간 사람의 표는 무효
            votes.values().removeIf(id::equals); // 나간 사람을 지목한 표도 무효
            ready.remove(id);   // 안 그러면 나간 사람이 "준비됨"으로 남아 정원 판정이 어긋난다
            // 쓰던 감방을 돌려놓는다. 반납하지 않으면 들락날락하는 사이 빈 방이 없다고
            // 판단해 뒤에 온 사람들이 한 방에 겹친다.
            Integer cell = cellOfPlayer.remove(id);
            if (cell != null) {
                takenCells.remove(cell);
            }
            votesDirty.set(true);
            readyDirty.set(true);
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

    /**
     * 클라 → 서버: 펀치 요청. 실제 사거리·전방·쿨다운 판정과 넉백 적용은 루프 스레드(tick)가 한다
     * — 권위 서버라 위치·속도는 루프 스레드만 만진다. 여기선 요청 플래그만 세운다.
     */
    public void punch(String id) {
        Player p = players.get(id);
        if (p != null) {
            // 순찰 중 주먹질도 "수상한 움직임"이다. 막지는 않는다 — 치는 건 자유고 대가만 치른다.
            if (patrol.active()) {
                catchSuspicious(p, "폭행");
            }
            p.requestPunch();
        }
    }

    /**
     * 채팅 발화(수신 스레드). 방에 있는 사람만, 도배 제한을 통과한 것만 큐에 넣는다.
     *
     * 실제 전송은 GameLoop이 tick 뒤에 한다 — Room이 브로커를 들면 게임 로직에 Spring 메시징이
     * 섞이고, 지금은 스냅샷도 펀치도 전부 "룸이 쌓고 루프가 보낸다"로 통일돼 있다.
     *
     * 순찰 중이어도 막지 않는다. 순찰에 걸리는 것은 <b>움직임</b>이지 말이 아니다 —
     * 오히려 숨죽여 말을 맞추는 시간이라 채팅이 필요하다.
     */
    public void chat(String playerId, String text) {
        Player p = players.get(playerId);
        if (p == null) {
            return; // 방에 없는 사람(입장 거절됐거나 이미 나갔다)
        }
        String body = sanitizeChat(text);
        if (body == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!claimChatSlot(p, now)) {
            return; // 도배. 조용히 버린다 — 오류를 돌려줘도 클라가 할 일이 없다.
        }
        pendingChat.add(new ChatEvent(p.id, p.nick, body, now));
    }

    /**
     * 봇의 발화(루프 스레드). 사람과 <b>같은 큐·같은 형식</b>으로 나간다.
     *
     * 굳이 별도 경로를 두지 않는 이유가 핵심이다 — 채팅에 봇 표시가 조금이라도 묻으면
     * 마지막 AI 지목 투표가 성립하지 않는다. 도배 제한도 사람과 똑같이 적용한다.
     */
    void botSay(String text) {
        Player bot = players.get(BOT_ID);
        if (bot == null) {
            return;
        }
        // 결말에 들어섰으면 입을 다문다. 그 화면은 엔딩 오버레이가 전부 덮어 채팅이 보이지 않는데,
        // 봇은 계속 계획을 세우므로 안 막으면 아무도 못 보는 말에 무료 한도만 쓴다(실측 2줄).
        if (phases.phase() == GamePhase.ENDED) {
            return;
        }
        String body = sanitizeChat(text);
        if (body == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!claimChatSlot(bot, now)) {
            return;
        }
        pendingChat.add(new ChatEvent(bot.id, bot.nick, body, now));
    }

    /**
     * 발화 자리를 원자적으로 잡는다. 잡았으면 true.
     *
     * 읽고-검사하고-쓰기를 CAS 한 번으로 묶는 게 핵심이다. 나눠 놓으면 같은 사람이 연타한
     * 메시지가 서로 다른 인바운드 스레드에서 동시에 처리되며 전부 낡은 값을 보고 통과한다
     * (실측: 연타 3회 중 2회 통과). CAS에 진 쪽은 다른 스레드가 방금 자리를 가져간 것이므로
     * 그대로 도배로 처리한다.
     */
    private static boolean claimChatSlot(Player p, long nowMs) {
        long prev = p.lastChatAtMs.get();
        if (nowMs - prev < CHAT_MIN_INTERVAL_MS) {
            return false;
        }
        return p.lastChatAtMs.compareAndSet(prev, nowMs);
    }

    /**
     * 본문 정리. 빈 말이면 null(보내지 않음).
     *
     * 개행을 공백으로 접는 이유: 클라 한 줄 UI라 여러 줄이 오면 레이아웃이 깨지고,
     * 개행 도배로 화면을 밀어 올릴 수도 있다.
     */
    private static String sanitizeChat(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > CHAT_MAX_LEN ? s.substring(0, CHAT_MAX_LEN) : s;
    }

    /**
     * 쌓인 채팅을 가져가고 비운다(루프 스레드). 없으면 빈 리스트.
     * 한 번에 CHAT_DRAIN_PER_TICK 개까지만 — 남은 것은 다음 tick에 나간다.
     */
    public List<ChatEvent> drainChat() {
        if (pendingChat.isEmpty()) {
            return List.of();
        }
        List<ChatEvent> out = new ArrayList<>();
        ChatEvent e;
        while (out.size() < CHAT_DRAIN_PER_TICK && (e = pendingChat.poll()) != null) {
            out.add(e);
        }
        return out;
    }

    /**
     * 펀치 요청 해소(루프 스레드). 요청한 사람마다 전방 콘·사거리 안 최근접 대상에게 넉백을 준다.
     * 결과(펀치 모션 대상 + 맞은 사람 + 방향)는 pendingPunches에 쌓여 이번 스냅샷에 실린다.
     *
     * 넉백 <b>속도</b>는 tick 이동 루프가 위치에 적분하므로 여기선 대상의 kx/kz만 채운다 —
     * 그래야 이번 tick부터 바로 밀린다.
     */
    private void resolvePunches(long nowMs) {
        for (Player p : players.values()) {
            if (!p.consumePunchRequest() || nowMs - p.lastPunchAtMs < PUNCH_COOLDOWN_MS) {
                continue;
            }
            p.lastPunchAtMs = nowMs;

            // 바라보는 방향(프론트 규약: rotationY = atan2(mx, mz) → forward = (sin, cos)).
            double fx = Math.sin(p.rotationY);
            double fz = Math.cos(p.rotationY);

            // 전방 콘 안 최근접 대상.
            Player target = null;
            double bestD2 = PUNCH_RANGE * PUNCH_RANGE;
            for (Player o : players.values()) {
                if (o == p) {
                    continue;
                }
                double ex = o.x - p.x;
                double ez = o.z - p.z;
                double d2 = ex * ex + ez * ez;
                if (d2 > bestD2) {
                    continue;
                }
                double d = Math.sqrt(d2);
                // 거의 겹쳐 있으면 방향 판정이 무의미 — 그냥 맞은 것으로 친다.
                if (d >= 1e-4 && (ex * fx + ez * fz) / d < PUNCH_CONE_DOT) {
                    continue;
                }
                target = o;
                bestD2 = d2;
            }

            double dirX;
            double dirZ;
            String victimId = null;
            if (target != null) {
                double ex = target.x - p.x;
                double ez = target.z - p.z;
                double d = Math.hypot(ex, ez);
                if (d < 1e-4) {
                    dirX = fx;
                    dirZ = fz;
                } else {
                    dirX = ex / d;
                    dirZ = ez / d;
                }
                target.applyKnockback(KNOCKBACK_SPEED * dirX, KNOCKBACK_SPEED * dirZ);
                victimId = target.id;
            } else {
                // 헛방: 넉백은 없지만 펀치 모션은 남들에게 보여야 한다.
                dirX = fx;
                dirZ = fz;
            }

            if (pendingPunches == null) {
                pendingPunches = new ArrayList<>();
            }
            pendingPunches.add(new PunchEvent(p.id, victimId, dirX, dirZ));
        }
    }

    /** 한 tick 진행: 진행 단계를 갱신하고, 각 플레이어의 이동 의도를 검증·적용한다. */
    public void tick(long nowMs) {
        // 시작 요청 소비. 여기(루프 스레드)에서만 시계를 건드린다.
        if (startRequested) {
            startRequested = false;
            if (phases.start(nowMs)) {
                phaseDirty.set(true);
                log.info("방 {} 게임 시작 → {}", roomId, phases.phase());
            }
        }

        // 단계 전이는 경과 시간으로만 결정된다(PhaseTimeline). 바뀐 tick에만 스냅샷에 싣는다.
        if (phases.advance(nowMs)) {
            phaseDirty.set(true);
            // 결말에 들어서면 최종 집계를 한 번 실어 보낸다(그 전 표가 그대로면 dirty가 안 서 있다).
            if (phases.phase() == GamePhase.ENDED) {
                votesDirty.set(true);
            }
            log.info("방 {} 단계 전환 → {}", roomId, phases.phase());
        }

        // 전원이 탈옥문을 열면(협동 오브젝트라 열리는 순간이 곧 팀 탈출 완료) 남은 MISSION/SHARING
        // 타이머와 무관하게 잠깐의 탈출 연출 뒤 색출(VOTE)로 넘어간다. 안 그러면 서버는 탈출을
        // 모른 채 방을 세워 두고, 플레이어는 투표 화면으로 못 넘어가 대기 상태로 남는다.
        long escapedAt = escapeCompletedAtMs;
        if (escapedAt != 0 && nowMs - escapedAt >= ESCAPE_TO_VOTE_DELAY_MS
                && phases.skipTo(GamePhase.VOTE, nowMs)) {
            phaseDirty.set(true);
            log.info("방 {} 전원 탈출 완료 → 색출(VOTE) 조기 전환", roomId);
        }

        // 펀치는 이동보다 먼저 해소한다 — 이번 tick의 넉백 속도가 아래 이동 적분에 바로 실리도록.
        resolvePunches(nowMs);

        // 순찰 시계는 페널티를 뺀 실제 경과를 본다(PhaseTimeline.rawElapsedMs 주석 참고).
        // 도는 구간은 감방 탈출·단서 공유뿐 — 그 밖에서는 무조건 NONE이라 자정이 크게
        // 당겨져 단계를 건너뛰어도 순찰이 남아 돌지 않는다.
        GamePhase now = phases.phase();
        boolean patrolWindow = now == GamePhase.MISSION || now == GamePhase.SHARING;
        if (patrol.advance(phases.rawElapsedMs(nowMs), patrolWindow)) {
            patrolDirty.set(true);
            log.info("방 {} 순찰 {}", roomId, patrol.state());
        }
        boolean patrolling = patrol.active();

        double dt = props.tickSeconds();
        double speed = props.speed();
        long timeout = props.inputTimeoutMs();
        double kbDecay = Math.exp(-dt / KNOCKBACK_TAU);

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
                // 순찰 중에는 봇도 멈춘다. 다만 가끔은 실수한다(botSlipChance) — 스크립트라
                // 늘 완벽히 멈추면 "한 번도 안 걸린 놈 = AI"가 되어 마지막 투표가 무의미해진다.
                if (patrolling && !patrol.botSlipsNow()) {
                    mx = 0;
                    mz = 0;
                }
            } else {
                mx = p.inputMoveX(nowMs, timeout);
                mz = p.inputMoveZ(nowMs, timeout);
                sprint = p.inputSprint(nowMs, timeout);
            }

            // 수상한 움직임 판정. 조작을 막지는 않는다 — 움직였다는 사실만 본다.
            // 시점 회전(rotationY)은 세지 않는다. 고개를 돌리는 것까지 걸면 너무 가혹하다.
            if (patrolling && (isMoving(mx, mz) || (!p.bot && p.inputJump(nowMs, timeout)))) {
                catchSuspicious(p, "이동");
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

            // 펀치 넉백: 외부 속도를 위치에 적분하고 지수 감쇠. 이동 입력과 독립적으로 밀린다.
            // (사람·봇 공통 — 봇도 맞으면 밀린다.) 아주 작아지면 0으로 끊어 미세 표류를 막는다.
            if (p.kx != 0 || p.kz != 0) {
                p.x += p.kx * dt;
                p.z += p.kz * dt;
                p.kx *= kbDecay;
                p.kz *= kbDecay;
                if (Math.abs(p.kx) < 0.01 && Math.abs(p.kz) < 0.01) {
                    p.kx = 0;
                    p.kz = 0;
                }
            }

            // 벽/소품 충돌 해석(열린 감방문은 통과, 발높이로 층 판정). 프론트 예측과 동일 로직.
            // XZ 밀어내기라 점프해도 장애물을 넘지 못한다 — 프론트 collision.ts와 이중 관리.
            double[] r = Collision.resolve(p.x, p.z, p.y - Player.GROUND_Y, openDoors);
            p.x = r[0];
            p.z = r[1];

            // 수직: 바닥은 그 좌표의 지지면(1층 0 / 계단 램프 / 2층 FLOOR2_Y). STEP_UP 이하의
            // 턱은 걸어서 스냅해 오르내린다(계단). 접지 중 점프 의도가 있으면 발사, 그 뒤엔
            // 중력으로 적분. 누르고 있으면 착지 즉시 다시 뛴다(연속 점프) — 별도 엣지 판정 없음.
            // 봇은 1층만 다니므로(웨이포인트가 전부 1층) 수직 적분을 건너뛴다 — y가 늘 GROUND_Y.
            if (!p.bot) {
                double floorY = Collision.groundHeight(p.x, p.z, p.y - Player.GROUND_Y) + Player.GROUND_Y;
                boolean grounded = p.vy <= 0 && p.y - floorY <= Collision.STEP_UP;
                if (grounded) {
                    p.y = floorY; // 계단·턱 스냅(내려갈 때 튀지 않게)
                    if (p.inputJump(nowMs, timeout)) {
                        p.vy = props.jumpSpeed();
                    }
                }
                if (!grounded || p.vy > 0) {
                    p.vy -= props.gravity() * dt;
                    p.y += p.vy * dt;
                    if (p.y <= floorY) {
                        p.y = floorY;
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

        resolvePlayerOverlaps();
        tick++;
    }

    // 대인 충돌 순회용 버퍼(핫패스 — tick마다 리스트를 새로 만들지 않는다).
    private Player[] overlapBuf = new Player[8];

    /**
     * 플레이어끼리도 실체가 있다 — 겹치면 반씩 밀어 벌린다(프론트 pushOutOfPlayer와 같은 규약,
     * 단 서버는 양쪽을 민다). 층이 다르면(발높이 차가 크면) 통과 — 2층과 1층은 딴 공간이다.
     * 민 뒤에는 벽 충돌을 다시 해석해 벽 안으로 밀려 들어가지 않게 한다.
     */
    private void resolvePlayerOverlaps() {
        int n = 0;
        for (Player p : players.values()) {
            if (n == overlapBuf.length) {
                overlapBuf = java.util.Arrays.copyOf(overlapBuf, n * 2);
            }
            overlapBuf[n++] = p;
        }
        final double min = Collision.PLAYER_R * 2;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Player a = overlapBuf[i];
                Player b = overlapBuf[j];
                if (Math.abs(a.y - b.y) > 1.6) {
                    continue;
                }
                double dx = b.x - a.x;
                double dz = b.z - a.z;
                double d2 = dx * dx + dz * dz;
                if (d2 >= min * min) {
                    continue;
                }
                double nx;
                double nz;
                double push;
                if (d2 > 1e-8) {
                    double d = Math.sqrt(d2);
                    nx = dx / d;
                    nz = dz / d;
                    push = (min - d) / 2;
                } else {
                    nx = 1; // 완전히 겹침: 아무 방향으로나
                    nz = 0;
                    push = min / 2;
                }
                a.x -= nx * push;
                a.z -= nz * push;
                b.x += nx * push;
                b.z += nz * push;
                double[] ra = Collision.resolve(a.x, a.z, a.y - Player.GROUND_Y, openDoors);
                a.x = ra[0];
                a.z = ra[1];
                double[] rb = Collision.resolve(b.x, b.z, b.y - Player.GROUND_Y, openDoors);
                b.x = rb[0];
                b.z = rb[1];
            }
        }
        // 순회 뒤 참조를 비워 나간 플레이어가 버퍼에 붙들리지 않게 한다.
        java.util.Arrays.fill(overlapBuf, 0, n, null);
    }

    /**
     * 퍼즐 해결 기록(협동). 방 생명주기 동안 유지된다.
     * 감방 자물쇠였다면 그 방 문도 함께 연다 — 사람·봇이 같은 경로를 타게 하려는 것이다.
     */
    /** 이동 의도가 "움직였다"고 볼 만한 크기인가. 부동소수 찌꺼기는 걸러낸다. */
    private boolean isMoving(double mx, double mz) {
        double eps = patrolProps.moveEpsilon();
        return Math.abs(mx) > eps || Math.abs(mz) > eps;
    }

    /**
     * 순찰에 걸렸다 — 자정을 앞당긴다.
     *
     * 페널티는 순찰 1회당 한 번뿐이다(Patrol.reportSuspicious). 셋이 동시에 움직였다고
     * 3분을 깎으면 한 번의 순간에 판이 끝나 버린다.
     */
    private void catchSuspicious(Player p, String what) {
        if (!patrol.reportSuspicious(p.id)) {
            return;
        }
        long penalty = patrolProps.penalty().toMillis();
        phases.penalize(penalty);
        // 남은 시간이 방금 바뀌었으니 단계 정보를 다시 실어 보내야 한다(안 그러면 클라
        // 카운트다운이 예전 값으로 계속 흐른다).
        phaseDirty.set(true);
        patrolDirty.set(true);
        log.info("방 {} 순찰 적발: {}({}) — 자정 {}초 단축", roomId, p.nick, what, penalty / 1000);
    }

    /**
     * 사람이 퍼즐을 풀었다. 순찰 중이었다면 그 행동 자체가 적발 사유다.
     *
     * 푸는 것 자체를 막지는 않는다 — 멈출지 말지는 플레이어가 정한다는 게 이 장치의 규칙이다.
     */
    public void solveByPlayer(String playerId, String objectId) {
        Player p = playerId == null ? null : players.get(playerId);
        if (p != null && patrol.active()) {
            catchSuspicious(p, "상호작용");
        }
        markSolved(objectId);
    }

    public void markSolved(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            return;
        }
        solvedIds.add(objectId);
        // 탈옥문이 열렸다 — 팀 전체 탈출 완료. tick이 이 시각을 보고 잠깐 뒤 색출로 넘긴다.
        if (ESCAPE_GATE_ID.equals(objectId) && escapeCompletedAtMs == 0) {
            escapeCompletedAtMs = System.currentTimeMillis();
        }
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

    /** (x,z)가 속한 감방 인덱스. 어느 감방에도 없으면 -1(개활지·다른 건물). */
    private static int cellOf(double x, double z) {
        for (int i = 0; i < CELL_CENTERS.length; i++) {
            if (Math.abs(x - CELL_CENTERS[i][0]) <= CELL_HALF_X
                    && Math.abs(z - CELL_CENTERS[i][1]) <= CELL_HALF_Z) {
                return i;
            }
        }
        return -1;
    }

    /** 두 점이 같은 감방 안인지. 개활지·다른 건물이면 false(감방만 서로 격리된 잠금 공간이라). */
    private static boolean sameCell(double ax, double az, double bx, double bz) {
        int a = cellOf(ax, az);
        return a >= 0 && a == cellOf(bx, bz);
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
        // 준비 상태도 같은 규약. 대기방에서만 쓰이지만 게임 중에도 굳이 지우지는 않는다.
        List<String> readyIds = readyDirty.getAndSet(false) ? new ArrayList<>(ready) : null;

        // 펀치는 일어난 tick에만 싣고 곧바로 비운다(다음 tick에 다시 실리면 모션이 반복된다).
        List<PunchEvent> punches = pendingPunches;
        pendingPunches = null;

        // 순찰도 로스터와 같은 규약 — 상태가 바뀔 때, 누가 걸릴 때, 입장할 때만 싣는다.
        String patrolState = null;
        Long patrolRemainMs = null;
        String patrolCaughtId = null;
        if (patrolDirty.getAndSet(false)) {
            patrolState = patrol.state().name();
            // 남은 시간도 순찰과 같은 시계(페널티 제외)로 재야 한다.
            patrolRemainMs = patrol.remainMs(phases.rawElapsedMs(nowMs));
            patrolCaughtId = patrol.caughtId();
        }

        return new WorldSnapshot(tick, states, roster, new ArrayList<>(solvedIds),
                new ArrayList<>(openDoors), phase, phaseRemainMs, voteList, aiId, readyIds,
                punches, patrolState, patrolRemainMs, patrolCaughtId);
    }
}
