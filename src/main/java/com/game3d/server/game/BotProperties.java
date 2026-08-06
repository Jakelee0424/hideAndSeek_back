package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml의 game.bot.* 설정. */
@ConfigurationProperties(prefix = "game.bot")
public record BotProperties(Llm llm, Punch punch) {

    /**
     * 봇이 <b>맞았을 때 되받아치는</b> 빈도.
     *
     * 넣은 이유: 마지막이 AI 지목 투표라 <b>"한 번도 안 때린 놈"</b>이 단서가 된다. 넉백은
     * 사람·봇 공통인데 {@code requestPunch}가 STOMP 세션에서만 올라와서, 세션이 없는 봇은
     * 원래 맞기만 했다.
     *
     * <p><b>2026-08-06부터 봇은 먼저 치지 않는다.</b> 예전엔 사거리 안에 사람이 들어오는 기회마다
     * 확률로 선제 공격을 했는데, 협동 퍼즐 중에 이유 없이 밀쳐지면 성가시고 시연에서 심사위원
     * 캐릭터를 봇이 먼저 두들기는 그림이 된다. 이제 계기는 하나뿐이다 —
     * 사람이 봇을 치면({@code BotBrain.tookPunch}) 돌아서서 <b>한 대만</b> 돌려준다.
     * ⚠️ 그 대신 아무도 봇을 안 때리면 봇도 한 판 내내 안 때린다. 위 "한 번도 안 때린 놈" 단서가
     * 다시 살아난다는 뜻이다(감수하고 택한 트레이드오프).
     *
     * @param enabled  false면 맞아도 되받아치지 않는다
     * @param minGapMs 한 번 되갚은 뒤 다음까지 최소 간격. 주고받는 난투를 막는다
     * @param chance   맞는 순간 굴리는 보복 확률. <b>1.0으로 두지 말 것</b> — "때리면 반드시
     *                 되받아치는 놈 = AI"가 되어 마지막 지목 투표가 무의미해진다
     * @param range    이 거리 안이어야 친다. Room.PUNCH_RANGE(2.2)보다 짧게 잡을 것 — 판정 밖에서
     *                 헛손질하면 "허공에 주먹질하는 놈"이 되어 오히려 이상하다
     */
    public record Punch(boolean enabled, long minGapMs, double chance, double range) {}

    /**
     * LLM(Groq) 느린 층 설정.
     *
     * ⚠️ 무료 티어 한도가 설계 제약이다(2026-07 실측). gpt-oss-20b는 1,000 요청/일 · 8,000 토큰/분
     * → 6초 주기(10콜/분)면 봇을 켜둔 채로 하루 100분이 끝이다. 주기를 줄이거나 방을 늘리기 전에
     * 반드시 다시 계산할 것. 상세는 docs/ai-bot-notes.md.
     */
    public record Llm(
            boolean enabled,
            /**
             * 돌려 쓸 모델 목록. 호출마다 라운드 로빈으로 고른다.
             *
             * Groq 무료 한도는 <b>모델별</b>로 따로 걸린다. 그래서 모델을 나눠 쓰면 TPM 상한이
             * 사실상 모델 수만큼 늘어난다. 반대로 같은 계정의 API 키를 여러 개 만들어 돌리는 건
             * 소용없다 — 키가 아니라 조직 단위로 재기 때문이다.
             */
            List<String> models,
            String baseUrl,
            String apiKey,
            long intervalMs,
            long timeoutMs,
            /** 429(한도 초과)를 받은 모델을 이 시간 동안 건너뛴다. */
            long cooldownMs
    ) {}
}
