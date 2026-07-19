package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 룸 레지스트리. 룸은 첫 입장 시 자동 생성된다(로비=프론트 라우팅). */
@Component
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final GameProperties props;
    private final PhaseProperties phaseProps;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    /** null이면 봇이 스크립트로만 돈다. */
    private final BotPlanner llm;
    private final long planIntervalMs;

    public RoomManager(GameProperties props, PhaseProperties phaseProps, BotProperties botProps, GroqBotPlanner groq) {
        this.props = props;
        this.phaseProps = phaseProps;
        BotProperties.Llm cfg = botProps.llm();
        // 키가 비었는데 켜져 있으면 매 주기 401을 맞는다. 그럴 바엔 아예 스크립트로 돌린다.
        // 모델 목록이 비어 있어도 마찬가지다 — 보낼 모델이 없으면 매번 400이다.
        boolean on = cfg.enabled() && cfg.apiKey() != null && !cfg.apiKey().isBlank()
                && cfg.models() != null && !cfg.models().isEmpty();
        this.llm = on ? groq : null;
        this.planIntervalMs = cfg.intervalMs();
        log.info("AI 봇 느린 층: {}", on
                ? String.join(" / ", cfg.models()) + " 라운드로빈 (" + cfg.intervalMs() + "ms 주기)"
                : "스크립트 전용");
        log.info("진행 단계: 온보딩 {}s → 미션 {}s → 공유 {}s → 투표 {}s (총 {}분)",
                phaseProps.onboarding().toSeconds(), phaseProps.mission().toSeconds(),
                phaseProps.sharing().toSeconds(), phaseProps.vote().toSeconds(),
                phaseProps.totalMs() / 60_000);
    }

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> new Room(id, props, phaseProps, llm, planIntervalMs));
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
