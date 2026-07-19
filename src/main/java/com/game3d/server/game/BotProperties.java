package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml의 game.bot.* 설정. */
@ConfigurationProperties(prefix = "game.bot")
public record BotProperties(Llm llm) {

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
