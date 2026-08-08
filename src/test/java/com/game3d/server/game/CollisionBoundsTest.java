package com.game3d.server.game;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 충돌 해석은 <b>절대 맵 밖으로 밀어내지 않는다.</b>
 *
 * <p>왜 이 시험이 있는가 — 2026-08-08 "연병장에서 갇혀서 이동이 안 된다" 신고의 원인이 이것이었다.
 * 연병장 벤치 셋이 담장에 박혀 있어서, 벤치와 담장 사이로 밀린 원(반경 {@code PLAYER_R})이
 * 갈 곳이 <b>담장 밖에만</b> 있었다(z −29.95, 경계는 −29.6). {@link Collision#resolve}가 경계 밖
 * 좌표를 돌려주면 다음 tick의 경계 clamp가 그 자리를 도로 벤치 안으로 집어넣는다 →
 * <b>밀림 ↔ clamp 무한 왕복</b>. 그동안 플레이어는 그 방향으로 한 발도 못 나간다.
 *
 * <p>이건 소품 하나를 옮겨 고칠 문제가 아니라 <b>규칙</b>이다 — 담장에 붙는 소품을 새로 놓을
 * 때마다 같은 함정이 생긴다. 그래서 맵 전체를 훑어 규칙으로 잠근다.
 * ⚠️ 프론트 game/collision.ts가 같은 규칙을 <b>따로</b> 구현한다(이중 관리) — 한쪽만 고치면
 * 클라 예측과 서버 권위가 어긋나 러버밴딩이 난다.
 */
class CollisionBoundsTest {

    private static final Set<String> ALL_CLOSED = Set.of();
    private static final double EPS = 1e-9;

    /** 맵 전체(0.2m 격자)를 훑어 밀어낸 자리가 경계 안인지 본다. 1층·2층 발높이 둘 다. */
    @Test
    void resolve는_언제나_경계_안을_돌려준다() {
        int outside = 0;
        String first = null;
        for (double feetY : new double[] {0, Collision.FLOOR2_Y}) {
            for (double x = -Collision.BOUND_X; x <= Collision.BOUND_X; x += 0.2) {
                for (double z = -Collision.BOUND_Z; z <= Collision.BOUND_Z; z += 0.2) {
                    double[] p = Collision.resolve(x, z, feetY, ALL_CLOSED);
                    if (Math.abs(p[0]) > Collision.BOUND_X + EPS || Math.abs(p[1]) > Collision.BOUND_Z + EPS) {
                        outside++;
                        if (first == null) {
                            first = String.format("(%.2f, %.2f, y=%.1f) → (%.2f, %.2f)", x, z, feetY, p[0], p[1]);
                        }
                    }
                }
            }
        }
        assertEquals(0, outside, "경계 밖으로 밀려나는 자리가 있다 — 첫 사례 " + first);
    }

    /**
     * 밀어낸 자리는 <b>고정점</b>이어야 한다: 한 번 더 풀어도 그대로여야 매 tick 자리가 튀지 않는다.
     * (경계 clamp까지 포함해 왕복이 없는지 보려면 resolve를 두 번 먹여 보면 된다.)
     */
    @Test
    void resolve_결과를_다시_풀어도_그대로다() {
        int unstable = 0;
        String first = null;
        for (double x = -Collision.BOUND_X; x <= Collision.BOUND_X; x += 0.2) {
            for (double z = -Collision.BOUND_Z; z <= Collision.BOUND_Z; z += 0.2) {
                double[] a = Collision.resolve(x, z, 0, ALL_CLOSED);
                double[] b = Collision.resolve(a[0], a[1], 0, ALL_CLOSED);
                // 벽 한복판처럼 여러 박스가 겹친 자리는 한 번에 안 풀릴 수 있다 — 서 있을 수 있는
                // 자리(= 이미 고정점인 곳)만 본다.
                boolean settled = Math.abs(a[0] - x) < EPS && Math.abs(a[1] - z) < EPS;
                if (settled && (Math.abs(b[0] - a[0]) > EPS || Math.abs(b[1] - a[1]) > EPS)) {
                    unstable++;
                    if (first == null) {
                        first = String.format("(%.2f, %.2f) → (%.2f, %.2f)", x, z, b[0], b[1]);
                    }
                }
            }
        }
        assertEquals(0, unstable, "다시 풀면 자리가 바뀌는 곳이 있다 — 첫 사례 " + first);
    }

    /** 연병장 벤치 뒤(옛 함정 자리)에서 실제로 빠져나올 수 있는가 — 회귀 고정. */
    @Test
    void 연병장_벤치_뒤에서_빠져나올_수_있다() {
        // 남벽 벤치 둘(cz −29.2)과 서벽 벤치 하나(cx −41.3) 뒤의 옛 함정 좌표.
        double[][] spots = {{-37, -29.5}, {-31, -29.5}, {-41.5, -25}};
        for (double[] s : spots) {
            double[] p = Collision.resolve(s[0], s[1], 0, ALL_CLOSED);
            assertTrue(Math.abs(p[0]) <= Collision.BOUND_X + EPS && Math.abs(p[1]) <= Collision.BOUND_Z + EPS,
                    "벤치 뒤에서 경계 밖으로 밀려났다: " + p[0] + ", " + p[1]);
            // 밀려난 자리가 다시 소품 안이면 안 된다(= 그 자리에서 멈춘다).
            double[] again = Collision.resolve(p[0], p[1], 0, ALL_CLOSED);
            assertEquals(p[0], again[0], EPS, "벤치 뒤 자리가 안정적이지 않다");
            assertEquals(p[1], again[1], EPS, "벤치 뒤 자리가 안정적이지 않다");
        }
    }
}
