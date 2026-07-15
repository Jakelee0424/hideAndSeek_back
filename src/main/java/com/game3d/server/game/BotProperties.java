package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 game.bot.* 설정. */
@ConfigurationProperties(prefix = "game.bot")
public record BotProperties(Llm llm) {

    /**
     * LLM(Groq) 느린 층 설정.
     *
     * ⚠️ 무료 티어 한도가 설계 제약이다(2026-07 기준 실측):
     *   - llama-3.1-8b-instant: 14,400 요청/일, 6,000 토큰/분
     *   - gpt-oss-20b / llama-3.3-70b-versatile: 1,000 요청/일 → 봇 하나로 83분이면 소진
     * 프롬프트가 약 340토큰이므로 6초 주기(10콜/분)면 분당 약 4,000토큰. 방이 둘 이상이면 넘는다.
     * 주기를 줄이거나 방을 늘릴 땐 한도를 다시 계산할 것.
     */
    public record Llm(
            boolean enabled,
            String model,
            String baseUrl,
            String apiKey,
            long intervalMs,
            long timeoutMs
    ) {}
}
