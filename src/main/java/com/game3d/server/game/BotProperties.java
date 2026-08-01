package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml의 game.bot.* 설정. */
@ConfigurationProperties(prefix = "game.bot")
public record BotProperties(Llm llm, Punch punch) {

    /**
     * 봇이 사람을 툭 치는 빈도.
     *
     * 넣은 이유: 마지막이 AI 지목 투표라 <b>"한 번도 안 때린 놈"</b>이 단서가 된다. 예전엔 봇이
     * 맞기만 했다 — 넉백은 사람·봇 공통인데 {@code requestPunch}가 STOMP 세션에서만 올라와서,
     * 세션이 없는 봇은 칠 방법이 아예 없었다.
     *
     * ⚠️ 빈도를 올리지 말 것. 협동 퍼즐 중에 계속 밀치면 성가시고, 시연에서 심사위원이 잡은
     * 캐릭터를 봇이 두들기는 그림이 된다. 기본값은 <b>한 판(15분)에 1~2회</b>가 되도록 잡았다:
     * 사거리 안에 사람이 들어오는 기회마다 {@code chance}로 굴리고, 치면 {@code minGapMs}는 쉰다.
     *
     * @param enabled  false면 예전처럼 아예 안 친다
     * @param minGapMs 한 번 친 뒤 다음까지 최소 간격
     * @param chance   기회(사거리·전방 안에 사람)마다 실제로 칠 확률
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
