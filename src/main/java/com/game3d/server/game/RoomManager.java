package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 룸 레지스트리. 룸은 첫 입장 시 자동 생성된다(로비=프론트 라우팅). */
@Component
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final GameProperties props;
    private final PhaseProperties phaseProps;
    private final PatrolProperties patrolProps;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    /** null이면 봇이 스크립트로만 돈다. */
    private final BotPlanner llm;
    private final long planIntervalMs;
    private final BotProperties.Punch punchCfg;

    public RoomManager(GameProperties props, PhaseProperties phaseProps,
                       PatrolProperties patrolProps, BotProperties botProps, GroqBotPlanner groq) {
        this.props = props;
        this.phaseProps = phaseProps;
        this.patrolProps = patrolProps;
        BotProperties.Llm cfg = botProps.llm();
        // 키가 비었는데 켜져 있으면 매 주기 401을 맞는다. 그럴 바엔 아예 스크립트로 돌린다.
        // 모델 목록이 비어 있어도 마찬가지다 — 보낼 모델이 없으면 매번 400이다.
        boolean on = cfg.enabled() && cfg.apiKey() != null && !cfg.apiKey().isBlank()
                && cfg.models() != null && !cfg.models().isEmpty();
        this.llm = on ? groq : null;
        this.planIntervalMs = cfg.intervalMs();
        this.punchCfg = botProps.punch();
        log.info("AI 봇 느린 층: {}", on
                ? String.join(" / ", cfg.models()) + " 라운드로빈 (" + cfg.intervalMs() + "ms 주기)"
                : "스크립트 전용");
        log.info("진행 단계: 탈옥 {}s(도입 내레이션 {}s 포함) → 색출 {}s (총 {}분)",
                phaseProps.play().toSeconds(), phaseProps.intro().toSeconds(),
                phaseProps.vote().toSeconds(), phaseProps.totalMs() / 60_000);

        // 봇 길찾기 전수 검사. 격자로 바꾸며 생긴 안전망이다 — 손으로 관리하던 웨이포인트
        // 시절엔 노드가 고립돼도 조용히 통과했고, 한 판 돌려 봇이 벽 앞에 서 있어야 알았다.
        // POI를 옮기거나 벽을 바꿔 길이 끊기면 여기서 부팅 로그로 드러난다.
        String unreachable = Nav.reachabilityReport();
        if (unreachable.isEmpty()) {
            log.info("봇 길찾기 전수 검사: POI {}곳이 서로 전부 도달 가능 · {}",
                    Interactables.all().size(), Nav.connectivityReport());
        } else {
            log.error("⚠️ 봇 길찾기 전수 검사 실패 — 아래 지점은 봇이 못 간다:\n{}", unreachable);
        }
    }

    /**
     * 테스트 방의 단계 길이. 혼자 들어가 결말(투표)까지 2분 남짓에 확인하려는 용도다.
     * 정식 20분을 다 기다리면 투표 화면을 볼 수가 없다.
     */
    private static final PhaseProperties TEST_PHASES = new PhaseProperties(
            Duration.ofSeconds(15),  // 도입 내레이션(단계가 아니라 PLAY 앞부분에 겹친다)
            Duration.ofSeconds(90),  // 플레이 — 옛 온보딩15+미션60+공유15와 같은 총량이다
            Duration.ofSeconds(45)   // 색출
    );

    /**
     * 테스트 방의 순찰 설정. 정식 값(20~30초 순찰, 90초 간격)은 90초짜리 창에 들어가지 않는다.
     * Patrol이 앞뒤로 20초씩 비워 두므로 실제 창은 35~70초 남짓이다 — 여기에 맞춰 줄였다.
     */
    private static final PatrolProperties TEST_PATROL = new PatrolProperties(
            true,
            1, 1,                    // 짧은 판이라 한 번만
            Duration.ofSeconds(8),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),   // 최소 간격(1회라 의미는 없지만 계산식이 쓴다)
            Duration.ofSeconds(3),   // 예고
            Duration.ofSeconds(10),  // 걸리면 자정 10초 단축
            0.05,
            2,                       // 간수 2명(운영과 동일)
            6.0,                     // 순찰이 8~10초뿐이라 빨리 훑도록 운영(2.2)보다 빠르게
            16.0,                    // 시야도 조금 넓게 — 짧은 판에서 마주치기는 해야 한다
            75.0,
            0.08                     // 봇 실수 확률(운영값과 동일하게 낮춤 — application.yml 주석 참고)
    );

    /**
     * 이 코드로 만든 방은 <b>대기 없이 바로 시작</b>하고 단계도 짧게 돈다.
     *
     * 정식 흐름은 전원 준비 → 방장 시작이라 혼자서는 게임을 볼 수 없다. 개발·시연 점검용
     * 뒷문이다. 방 코드는 대문자로 비교한다(로비가 입력을 대문자로 바꿔 보낸다).
     */
    private boolean isTestRoom(String roomId) {
        String code = props.testRoomCode();
        return code != null && !code.isBlank() && code.equalsIgnoreCase(roomId);
    }

    /**
     * 방 얻기(없으면 생성). <b>끝난 방은 그대로 돌려주지 않고 새 방으로 갈아 끼운다.</b>
     *
     * Room에 상태 리셋이 없고 {@link PhaseTimeline#start}는 멱등이라, 끝난 방을 돌려주면
     * 다음 판이 시작되지도 않은 채 이전 판의 solvedIds·openDoors를 그대로 물려받는다
     * (감방·별관·배수관이 다 열린 채로 시작). {@link #remove}의 보관 시간({@code ENDED_KEEP_MS})은
     * 엔딩 연출용이지, 다시 들어온 사람에게 끝난 판을 보여 주라는 뜻이 아니다.
     *
     * 갈아 끼운 방은 맵에서 빠지므로 루프가 더 이상 돌리지 않는다 — 아직 엔딩 화면을 보고
     * 있던 사람은 스냅샷이 끊기지만, 이미 끝난 판이라 잃을 진행 상태가 없다.
     */
    public Room getOrCreate(String roomId) {
        return rooms.compute(roomId, (id, cur) -> {
            if (cur != null && !cur.ended()) {
                return cur;
            }
            if (cur != null) {
                log.info("방 {} 교체 — 끝난 판에 새로 입장했다(이전 판 상태를 물려주지 않는다)", id);
            }
            boolean test = isTestRoom(id);
            if (test) {
                log.info("테스트 방 {} 생성 — 즉시 시작 + 단축 단계({}초)", id, TEST_PHASES.totalMs() / 1000);
            }
            return new Room(id, props, test ? TEST_PHASES : phaseProps,
                    test ? TEST_PATROL : patrolProps, llm, planIntervalMs, punchCfg, test);
        });
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public Collection<Room> rooms() {
        return rooms.values();
    }

    /**
     * 룸 정리(루프에서 주기 호출). <b>비었거나</b> 결말이 난 지 오래된 방을 지운다.
     *
     * ⚠️ 방 제거가 이 게임의 유일한 초기화 수단이다(Room에 상태 리셋이 없고 PhaseTimeline.start는
     * 멱등이다). 그래서 "끝난 방"은 사람이 남아 있어도 치운다 — 안 치우면 그 방은 감방문이 다
     * 열린 채로 굳어, 다시 들어온 사람이 이전 판 상태를 그대로 본다.
     *
     * 지운 뒤 같은 방 코드로 들어오면 {@link #getOrCreate}가 새 방을 만든다.
     */
    public void remove(String roomId, long nowMs) {
        rooms.computeIfPresent(roomId, (id, room) -> {
            if (room.isEmpty()) {
                return null;
            }
            if (room.expired(nowMs)) {
                log.info("방 {} 정리 — 결말 뒤 보관 시간이 지났다(다음 입장은 새 방)", id);
                return null;
            }
            return room;
        });
    }
}
