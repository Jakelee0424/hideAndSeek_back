package com.game3d.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml의 game.patrol.* 설정 — 정기 순찰(방해 요소).
 *
 * 순찰이 도는 동안 움직이거나 무언가를 건드리면 걸린다. 시스템이 조작을 막지는 않는다 —
 * 멈추는 건 플레이어 몫이고, 걸리면 자정이 그만큼 앞당겨진다.
 *
 * 길이는 Duration이라 yml에 "25s"처럼 쓴다. 테스트 방은 20분을 기다릴 수 없으므로
 * {@link RoomManager}가 짧은 값으로 갈아 끼운다.
 */
@ConfigurationProperties(prefix = "game.patrol")
public record PatrolProperties(
        boolean enabled,
        /** 한 판에 도는 횟수의 범위(둘 다 포함). 시작할 때 이 사이에서 뽑는다. */
        int minCount,
        int maxCount,
        /** 1회 지속 시간의 범위. 매번 이 사이에서 뽑는다. */
        Duration minDuration,
        Duration maxDuration,
        /** 순찰 사이 최소 간격. 연달아 터져 손쓸 틈이 없어지는 걸 막는다. */
        Duration minGap,
        /** 순찰 시작 전 예고 시간. 이 동안 멈출 준비를 한다(예고 중에는 걸리지 않는다). */
        Duration warn,
        /** 걸렸을 때 앞당겨지는 자정까지의 시간. */
        Duration penalty,
        /** 이 크기를 넘는 이동 의도를 "움직였다"로 본다. 부동소수 찌꺼기를 걸러내는 값. */
        double moveEpsilon,
        /**
         * 봇이 순찰 중에 실수할 확률(0~1). 순찰마다 새로 뽑는다.
         *
         * 0으로 두면 안 된다. 봇은 스크립트라 완벽하게 멈출 수 있는데, 사람은 반드시 언젠가
         * 흘린다 — "한 번도 안 걸린 놈"이 곧 AI가 되어 마지막 지목 투표가 무의미해진다.
         */
        double botSlipChance
) {

    /** 이번 순찰의 지속 시간(ms). min~max 사이에서 뽑는다. */
    long pickDurationMs(java.util.random.RandomGenerator rnd) {
        long lo = minDuration.toMillis();
        long hi = Math.max(lo, maxDuration.toMillis());
        return lo == hi ? lo : rnd.nextLong(lo, hi + 1);
    }

    /** 이번 판의 순찰 횟수. min~max 사이에서 뽑는다. */
    int pickCount(java.util.random.RandomGenerator rnd) {
        int lo = Math.max(0, minCount);
        int hi = Math.max(lo, maxCount);
        return lo == hi ? lo : rnd.nextInt(lo, hi + 1);
    }
}
