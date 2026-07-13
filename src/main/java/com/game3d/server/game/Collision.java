package com.game3d.server.game;

import java.util.Set;

/**
 * 정적 맵 충돌(권위). 플레이어=원(반경 PLAYER_R), 장애물=XZ 평면 AABB, 벽=경계.
 * 원-AABB 최소침투 밀어내기로 해석한다.
 *
 * ⚠️ 프론트 game/collision.ts 와 장애물 정의·해석이 반드시 동일해야 한다
 *    (서버 권위 + 클라 예측이 어긋나면 러버밴딩). 한쪽 바꾸면 양쪽 반영.
 */
final class Collision {

    static final double PLAYER_R = 0.4;
    static final double ROOM_INNER = 10.4; // 벽 안쪽면 - 플레이어 반경 (맵 ROOM=11, 벽두께 0.4)

    /** solvableId != null 이고 해결되면 통과 가능(문). */
    private record Box(double cx, double cz, double hx, double hz, String solvableId) {}

    private static final Box[] OBSTACLES = {
        new Box(3, 3, 0.5, 0.5, null),     // crate
        new Box(4, 3, 0.5, 0.5, null),     // crate
        new Box(-4, -5, 0.5, 0.5, null),   // crate
        new Box(5, -4, 0.45, 0.45, null),  // lockbox
        new Box(-8, 0, 0.8, 0.125, "door-1"), // door: 해결 시 통과
    };

    private Collision() {}

    /** (x,z)를 벽 경계 + 장애물 밖으로 밀어낸 위치를 반환. */
    static double[] resolve(double x, double z, Set<String> solved) {
        x = clamp(x, -ROOM_INNER, ROOM_INNER);
        z = clamp(z, -ROOM_INNER, ROOM_INNER);

        final double r = PLAYER_R;
        for (Box b : OBSTACLES) {
            if (b.solvableId() != null && solved.contains(b.solvableId())) {
                continue;
            }
            double nx = clamp(x, b.cx() - b.hx(), b.cx() + b.hx());
            double nz = clamp(z, b.cz() - b.hz(), b.cz() + b.hz());
            double dx = x - nx;
            double dz = z - nz;
            double d2 = dx * dx + dz * dz;
            if (d2 >= r * r) {
                continue;
            }
            if (d2 > 1e-8) {
                double d = Math.sqrt(d2);
                double push = (r - d) / d;
                x += dx * push;
                z += dz * push;
            } else {
                // 중심이 박스 내부: 침투가 작은 축으로 밀어냄
                double penX = b.hx() + r - Math.abs(x - b.cx());
                double penZ = b.hz() + r - Math.abs(z - b.cz());
                if (penX < penZ) {
                    x += Math.signum(x - b.cx()) * penX;
                } else {
                    z += Math.signum(z - b.cz()) * penZ;
                }
            }
        }
        return new double[] {x, z};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
