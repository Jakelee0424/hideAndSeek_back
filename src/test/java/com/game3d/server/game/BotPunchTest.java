package com.game3d.server.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 봇 펀치 규칙(2026-08-06): <b>봇은 먼저 치지 않는다.</b> 사람이 봇을 쳤을 때만
 * 돌아서서 <b>한 대</b> 돌려준다.
 *
 * <p>실주행(STOMP 프로브)으로는 이걸 확인하기가 어렵다 — 사람과 봇은 서로 다른 감방에서
 * 시작하고 문은 잠겨 있어, 봇에게 다가가 때리는 상황을 만드는 것부터가 한 판이다.
 * 규칙 자체는 {@link BotBrain}에 닫혀 있으므로 여기서 직접 검증한다.
 *
 * <p>확률({@code chance})은 1.0으로 두고 <b>규칙만</b> 본다. 운영값은 application.yml(0.7)이고,
 * 1.0으로 두면 안 되는 이유는 {@link BotProperties.Punch} 주석 참고.
 */
class BotPunchTest {

    /** enabled, minGapMs, chance, range — 확률만 1.0으로 고정한 운영 설정. */
    private static final BotProperties.Punch CFG = new BotProperties.Punch(true, 20_000, 1.0, 1.6);

    /** 0 언저리의 시각은 "아직 초기화 안 됨"과 헷갈릴 수 있어 넉넉히 띄운 기준시각을 쓴다. */
    private static final long T0 = 1_000_000L;

    private static final long TICK_MS = 50;

    private BotBrain brain;
    private Player bot;

    private void setUp() {
        brain = new BotBrain(null, 6000, CFG, s -> { });
        bot = new Player("bot-1", "1234", 0, 0, brain);
        bot.rotationY = 0; // +z를 본다(rotationY = atan2(dx, dz) 규약)
    }

    /** 봇 앞 dist m 지점에 선 사람. rotationY 0 기준 정면이다. */
    private static Player humanAt(double x, double z) {
        return new Player("human-1", "5678", x, z);
    }

    /** [from, from+durationMs) 동안 tick을 돌리며 봇이 친 횟수와 첫 시각을 센다. */
    private int[] countPunches(Player human, long from, long durationMs) {
        int hits = 0;
        long firstAt = -1;
        for (long t = from; t < from + durationMs; t += TICK_MS) {
            if (brain.wantsPunch(bot, List.of(bot, human), t, false)) {
                hits++;
                if (firstAt < 0) {
                    firstAt = t - from;
                }
            }
        }
        return new int[] {hits, (int) firstAt};
    }

    @Test
    void 맞지_않으면_한_판_내내_먼저_치지_않는다() {
        setUp();
        Player human = humanAt(0, 1.0); // 사거리(1.6) 안, 정면 — 옛 규칙이었다면 칠 자리다

        // 15분(한 판)을 통째로 돌린다. 옛 규칙은 여기서 여러 번 쳤다.
        int[] r = countPunches(human, T0, 15 * 60 * 1000L);

        assertEquals(0, r[0], "봇이 맞지도 않았는데 먼저 쳤다");
    }

    @Test
    void 맞으면_한_대만_돌려준다() {
        setUp();
        Player human = humanAt(0, 1.0);

        brain.tookPunch("human-1", T0);
        int[] r = countPunches(human, T0, 30_000);

        assertEquals(1, r[0], "보복은 정확히 한 대여야 한다(계속 치면 난투가 된다)");
        assertTrue(r[1] >= 400, "즉답(반응 지연 없음)은 기계로 보인다 — 실제 " + r[1] + "ms");
        assertTrue(r[1] <= 1300, "반응이 너무 늦다 — 실제 " + r[1] + "ms");
    }

    @Test
    void 되갚은_직후_또_맞아도_바로_되받지_않는다() {
        setUp();
        Player human = humanAt(0, 1.0);

        brain.tookPunch("human-1", T0);
        assertEquals(1, countPunches(human, T0, 5_000)[0]);

        // minGapMs(20s) 안에 다시 맞았다 → 이번엔 참는다.
        brain.tookPunch("human-1", T0 + 6_000);
        assertEquals(0, countPunches(human, T0 + 6_000, 12_000)[0], "쉬는 시간 안에 되받아쳤다");

        // 쉬는 시간이 지난 뒤 맞으면 다시 돌려준다.
        brain.tookPunch("human-1", T0 + 25_000);
        assertEquals(1, countPunches(human, T0 + 25_000, 10_000)[0], "쉬는 시간이 지났는데 안 돌려줬다");
    }

    @Test
    void 유효_기간이_지나면_잊는다() {
        setUp();
        Player human = humanAt(0, 8.0); // 사거리 밖 — 창이 닫힐 때까지 못 친다

        brain.tookPunch("human-1", T0);
        assertEquals(0, countPunches(human, T0, 12_000)[0]);

        // 창(약 9.2초)이 닫힌 뒤에 코앞으로 와도 뒤늦게 치지 않는다.
        assertEquals(0, countPunches(humanAt(0, 1.0), T0 + 12_000, 5_000)[0], "뒤늦게 쫓아가 쳤다");
    }

    @Test
    void 등_뒤에_있으면_돌아설_때까지_기다린다() {
        setUp();
        Player behind = humanAt(0, -1.0); // 사거리 안이지만 등 뒤

        brain.tookPunch("human-1", T0);
        assertEquals(0, countPunches(behind, T0, 5_000)[0], "뒤통수를 쳤다");

        // 대신 그쪽을 보라고 알려 준다(Room이 이 각도로 몸을 돌린다).
        Double aim = brain.revengeAim(bot, List.of(bot, behind), T0 + 2_000);
        assertNotNull(aim, "보복 중인데 볼 방향을 안 알려준다");
        assertEquals(Math.PI, Math.abs(aim), 1e-6);

        // 다 돌아섰으면 그때 친다.
        bot.rotationY = Math.PI;
        assertEquals(1, countPunches(behind, T0 + 2_000, 5_000)[0]);
    }

    @Test
    void 순찰_중에는_맞아도_참는다() {
        setUp();
        Player human = humanAt(0, 1.0);
        brain.tookPunch("human-1", T0);

        for (long t = T0; t < T0 + 5_000; t += TICK_MS) {
            assertFalse(brain.wantsPunch(bot, List.of(bot, human), t, true),
                    "순찰 중 폭행은 적발이라 자정이 깎인다 — 봇이 팀에 그 벌을 안기면 안 된다");
        }
    }

    @Test
    void 사거리_밖이면_다가가고_너무_멀면_포기한다() {
        setUp();
        Player near = humanAt(0, 4.0); // 사거리(1.6) 밖, 포기 거리(8) 안
        brain.tookPunch("human-1", T0);

        double[] mv = brain.steer(bot, List.of(bot, near), Set.of(), Set.of(), T0 + 1_500);
        assertEquals(0.0, mv[0], 1e-9);
        assertEquals(1.0, mv[1], 1e-9, "때린 사람 쪽으로 가야 한다");

        // 포기 거리 밖으로 달아나면 더는 쫓지 않는다 — 맵을 가로질러 따라다니면 게임을 방해한다.
        setUp();
        Player far = humanAt(0, 12.0);
        brain.tookPunch("human-1", T0);
        brain.steer(bot, List.of(bot, far), Set.of(), Set.of(), T0 + 1_500);
        assertNull(brain.revengeAim(bot, List.of(bot, far), T0 + 1_600), "너무 먼 상대를 아직 쫓고 있다");
    }

    /** 봇끼리는 아무 일도 없다 — Room이 사람이 친 경우에만 tookPunch를 부른다(그건 Room 쪽 규칙). */
    @Test
    void 설정이_꺼져_있으면_맞아도_안_친다() {
        brain = new BotBrain(null, 6000, new BotProperties.Punch(false, 20_000, 1.0, 1.6), s -> { });
        bot = new Player("bot-1", "1234", 0, 0, brain);
        Player human = humanAt(0, 1.0);

        brain.tookPunch("human-1", T0);
        assertEquals(0, countPunches(human, T0, 10_000)[0]);
    }
}
