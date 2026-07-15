package com.game3d.server.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Groq(OpenAI 호환 API)로 다음 목표를 정하는 느린 층.
 *
 * Spring의 RestClient 대신 JDK HttpClient를 쓴다 — Boot 4의 스타터/설정 관례가 3.x와 달라질 수 있어
 * (CLAUDE.md 경고) 표준 API 쪽이 안전하고, 새 의존성도 필요 없다.
 *
 * ⚠️ Boot 4는 Jackson 3(tools.jackson.*)을 쓴다. com.fasterxml.jackson.databind는 클래스패스에 없고,
 *    JacksonException은 unchecked다(2.x의 JsonProcessingException과 다름).
 *
 * 이 클래스는 실패를 숨기지 않고 던진다. 삼키는 건 BotBrain의 몫.
 */
@Component
class GroqBotPlanner implements BotPlanner {

    private static final Logger log = LoggerFactory.getLogger(GroqBotPlanner.class);

    /**
     * 응답은 JSON만 받는다(response_format=json_object). 스키마를 프롬프트에 박아두는 이유:
     * llama-3.1-8b-instant는 json_object 모드에서 키 이름까지 보장해주진 않는다.
     */
    private static final String SYSTEM = """
            너는 방탈출 게임의 AI 동료다. 사람 플레이어가 탈출하도록 돕는다.
            상태를 보고 다음 목표 하나만 정해 JSON으로만 답한다.
            JSON 스키마: {"action":"GOTO_PUZZLE|GOTO_NOTE|FOLLOW_PLAYER|IDLE","targetId":"위 목록의 id 중 하나(IDLE이면 null)","reason":"20자 이내"}
            규칙: door-1이 최종 탈출구다. 쪽지(note)는 자물쇠 코드 힌트를 준다. 아직 아무것도 안 풀렸으면 힌트부터 확인하는 게 좋다. targetId는 반드시 주어진 id만 쓴다.""";

    private final HttpClient http;
    private final ObjectMapper json;
    private final BotProperties.Llm cfg;

    GroqBotPlanner(ObjectMapper json, BotProperties props) {
        this.json = json;
        this.cfg = props.llm();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.timeoutMs()))
                .build();
    }

    @Override
    public Goal plan(BotContext ctx) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(ctx), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            // 429(한도)·401(키) 모두 여기로. 봇은 스크립트 목표로 계속 움직이므로 게임은 멈추지 않는다.
            throw new IOException("Groq " + res.statusCode() + ": " + res.body());
        }

        String content = json.readTree(res.body()).path("choices").path(0).path("message").path("content").asString("");
        return parseGoal(content);
    }

    /** 월드 상태를 최소 토큰으로 직렬화. 필드를 늘리기 전에 BotProperties의 한도 계산을 다시 볼 것. */
    private String requestBody(BotContext ctx) {
        ObjectNode self = json.createObjectNode();
        self.put("x", round(ctx.x()));
        self.put("z", round(ctx.z()));

        ArrayNode pois = json.createArrayNode();
        for (BotContext.PoiView p : ctx.pois()) {
            ObjectNode n = pois.addObject();
            n.put("id", p.id());
            n.put("x", round(p.x()));
            n.put("z", round(p.z()));
            n.put("label", p.label());
            n.put("solved", p.solved());
        }

        ArrayNode mates = json.createArrayNode();
        for (BotContext.MateView m : ctx.mates()) {
            ObjectNode n = mates.addObject();
            n.put("id", m.id());
            n.put("nick", m.nick());
            n.put("x", round(m.x()));
            n.put("z", round(m.z()));
        }

        ObjectNode state = json.createObjectNode();
        state.set("self", self);
        state.set("pois", pois);
        state.set("mates", mates);
        state.put("lastArrivedId", ctx.lastArrivedId());

        ObjectNode sys = json.createObjectNode();
        sys.put("role", "system");
        sys.put("content", SYSTEM);
        ObjectNode user = json.createObjectNode();
        user.put("role", "user");
        user.put("content", json.writeValueAsString(state));

        ObjectNode body = json.createObjectNode();
        body.put("model", cfg.model());
        body.put("temperature", 0.3);
        body.put("max_tokens", 120);
        body.set("response_format", json.createObjectNode().put("type", "json_object"));
        body.set("messages", json.createArrayNode().add(sys).add(user));
        return json.writeValueAsString(body);
    }

    /** 모델 응답 → Goal. 스키마를 벗어나면 null(호출자가 기존 목표 유지). targetId 검증은 BotBrain이 한다. */
    private Goal parseGoal(String content) {
        try {
            JsonNode n = json.readTree(content);
            Goal.Action action = Goal.Action.valueOf(n.path("action").asString(""));
            // JSON null을 그냥 문자열로 읽으면 "null"이 나온다. 명시적으로 걸러야 IDLE이 성립한다.
            JsonNode t = n.path("targetId");
            String targetId = t.isNull() || t.isMissingNode() ? null : t.asString(null);
            if (log.isDebugEnabled()) {
                log.debug("봇 계획: {} {} ({})", action, targetId, n.path("reason").asString(""));
            }
            return action == Goal.Action.IDLE ? Goal.IDLE : new Goal(action, targetId);
        } catch (IllegalArgumentException | JacksonException e) {
            // 모델이 스키마를 벗어난 경우(없는 action 이름 등). 게임은 스크립트 목표로 계속 돈다.
            log.warn("봇 계획 파싱 실패, 무시함: {}", content);
            return null;
        }
    }

    /** 소수점 1자리면 충분하다. 6.123456789가 토큰을 잡아먹는 걸 막는다. */
    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
