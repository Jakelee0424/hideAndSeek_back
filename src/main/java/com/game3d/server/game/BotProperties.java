package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
            String model,
            String baseUrl,
            String apiKey,
            long intervalMs,
            long timeoutMs
    ) {}
}
