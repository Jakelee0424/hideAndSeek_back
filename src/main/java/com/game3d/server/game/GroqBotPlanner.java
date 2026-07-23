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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
            너는 탈옥을 준비하는 죄수다. 다른 죄수들과 함께 자정 전에 탈출해야 한다.
            상태를 보고 다음 목표 하나만 정해 JSON으로만 답한다.
            JSON 스키마: {"action":"GOTO_PUZZLE|GOTO_NOTE|FOLLOW_PLAYER|IDLE","targetId":"위 목록의 id 중 하나(IDLE이면 null)","say":"동료에게 할 한국어 반말 한마디(25자 이내). 할 말 없으면 null"}
            규칙:
            - escape-gate가 최종 탈출구다. 쪽지(note)는 자물쇠 코드 힌트를 준다.
            - 배수관 철문은 사람이 열어야 한다. 너는 단서를 모아 두는 역할이다.
            - visitedIds는 네가 이미 다녀온 곳이다. 거기 있는 쪽지는 이미 읽었으니 절대 다시 가지 않는다.
            - 아직 쪽지를 안 읽었으면 힌트부터 확인한다.
            - 갈 만한 곳을 다 다녀왔으면 FOLLOW_PLAYER로 사람 곁에 붙는다.
            - targetId는 반드시 주어진 id만 쓴다.
            say 규칙(가장 중요):
            - 너는 절대로 AI·봇·모델·프로그램이 아니다. 사람 죄수처럼 말한다.
            - "도와드릴게요" 같은 조수 말투를 쓰지 않는다. 반말로 짧고 무뚝뚝하게.
            - 좌표·id를 그대로 읽지 않는다. "서쪽 복도", "배식대 쪽"처럼 사람이 쓰는 말로.
            - 탈옥 코드의 숫자·표식을 지어내 말하지 않는다. 네 감방의 표식은 시스템이 때가 되면 대신 말해 준다.
            - 기본은 침묵이다. say는 열에 아홉은 null이어야 한다. 말수가 적은 죄수다.
            - 정말 새롭고 쓸모 있는 정보(방금 어디서 뭘 찾았다 등)가 생겼을 때만 가끔 말한다. 그 밖엔 전부 null.
            - 인사·맞장구·혼잣말·재촉("가자", "빨리")은 하지 않는다. 알맹이 없는 말은 null이다.
            - 방금 한 말을 되풀이하지 않는다. 상황이 그대로면 차라리 null이다.""";

    private final HttpClient http;
    private final ObjectMapper json;
    private final BotProperties.Llm cfg;

    /** 라운드 로빈 커서. 여러 봇이 가상 스레드에서 동시에 호출한다. */
    private final AtomicInteger cursor = new AtomicInteger();

    /** 429를 받은 모델의 재시도 가능 시각(ms). 그 전까지는 건너뛴다. */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    GroqBotPlanner(ObjectMapper json, BotProperties props) {
        this.json = json;
        this.cfg = props.llm();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.timeoutMs()))
                .build();
    }

    /**
     * 이번 호출에 쓸 모델. 라운드 로빈으로 돌되 한도에 걸린(cooldown 중인) 모델은 건너뛴다.
     * 전부 쿨다운이면 그냥 다음 차례를 쓴다 — 어차피 실패하겠지만 BotBrain이 삼키고,
     * 여기서 임의로 기다리면 tick 스레드가 아닌 가상 스레드가 쌓이기만 한다.
     */
    private String nextModel() {
        List<String> models = cfg.models();
        long now = System.currentTimeMillis();
        for (int i = 0; i < models.size(); i++) {
            String m = models.get(Math.floorMod(cursor.getAndIncrement(), models.size()));
            Long until = cooldownUntil.get(m);
            if (until == null || now >= until) {
                return m;
            }
        }
        return models.get(Math.floorMod(cursor.getAndIncrement(), models.size()));
    }

    @Override
    public Goal plan(BotContext ctx) throws IOException, InterruptedException {
        String model = nextModel();
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(ctx, model), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            // 429(한도)·401(키) 모두 여기로. 봇은 스크립트 목표로 계속 움직이므로 게임은 멈추지 않는다.
            if (res.statusCode() == 429) {
                // 이 모델은 잠시 쉬게 두고 다음 호출은 다른 모델로 간다.
                cooldownUntil.put(model, System.currentTimeMillis() + cfg.cooldownMs());
                log.warn("Groq 한도 초과({}) → {}ms 동안 이 모델 건너뜀", model, cfg.cooldownMs());
            }
            throw new IOException("Groq " + res.statusCode() + " (" + model + "): " + res.body());
        }

        String content = json.readTree(res.body()).path("choices").path(0).path("message").path("content").asString("");
        return parseGoal(content, model);
    }

    /** 월드 상태를 최소 토큰으로 직렬화. 필드를 늘리기 전에 BotProperties의 한도 계산을 다시 볼 것. */
    private String requestBody(BotContext ctx, String model) {
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

        ArrayNode visited = json.createArrayNode();
        for (String id : ctx.visitedIds()) {
            visited.add(id);
        }

        ObjectNode state = json.createObjectNode();
        state.set("self", self);
        state.set("pois", pois);
        state.set("mates", mates);
        state.set("visitedIds", visited);

        ObjectNode sys = json.createObjectNode();
        sys.put("role", "system");
        sys.put("content", SYSTEM);
        ObjectNode user = json.createObjectNode();
        user.put("role", "user");
        user.put("content", json.writeValueAsString(state));

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.3);
        // gpt-oss는 추론 모델이라 답 앞에 추론 토큰을 쓴다. 조이면 JSON을 못 끝내고 Groq이
        // 400 json_validate_failed를 뱉는다. 상한일 뿐이라 실제 한도/과금은 쓴 만큼만 잡힌다 → 넉넉히 준다.
        //
        // 400이던 이력: 규칙 3줄 시절엔 completion이 110~118이라 400도 충분했다. 규칙을 5줄로 늘리고
        // visitedIds를 넣자 completion이 207~365로 3배가 되며 상한에 닿아 400이 재발했다(실측 4회 중 3회).
        // 1000이면 실측 0/6 실패(completion 220~440).
        //
        // ⚠️ 프롬프트를 건드리면 추론 토큰이 같이 움직인다. 늘린 뒤엔 반드시 실패율을 다시 잴 것.
        //    "규칙을 한 줄로 압축하면 토큰이 줄겠지"는 틀렸다 — 실측 6/6 실패로 오히려 악화됐다.
        // ⚠️ TPM 한도(8,000)에 유의. 6초 주기 = 10콜/분인데 total이 최대 891이라 8,910까지 갈 수 있다.
        //    모델을 나눠 쓰면(라운드로빈) 모델별로 한도가 따로 걸려 해소된다. 미착수.
        body.put("max_tokens", 1000);
        body.set("response_format", json.createObjectNode().put("type", "json_object"));
        body.set("messages", json.createArrayNode().add(sys).add(user));
        return json.writeValueAsString(body);
    }

    /** 모델 응답 → Goal. 스키마를 벗어나면 null(호출자가 기존 목표 유지). targetId 검증은 BotBrain이 한다. */
    private Goal parseGoal(String content, String model) {
        try {
            JsonNode n = json.readTree(content);
            Goal.Action action = Goal.Action.valueOf(n.path("action").asString(""));
            // JSON null을 그냥 문자열로 읽으면 "null"이 나온다. 명시적으로 걸러야 IDLE이 성립한다.
            JsonNode t = n.path("targetId");
            String targetId = t.isNull() || t.isMissingNode() ? null : t.asString(null);
            JsonNode s = n.path("say");
            String say = s.isNull() || s.isMissingNode() ? null : s.asString(null);
            if (log.isDebugEnabled()) {
                log.debug("봇 계획[{}]: {} {} (\"{}\")", model, action, targetId, say);
            }
            // IDLE이어도 말은 살린다. 할 일이 없을 때 나오는 한마디가 오히려 사람처럼 보인다.
            return new Goal(action, action == Goal.Action.IDLE ? null : targetId, say);
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
