package com.game3d.server.game;

import java.util.Set;

/**
 * 정적 맵 충돌(권위). 플레이어=원(반경 PLAYER_R), 벽=XZ 평면 AABB.
 * 원-AABB 최소침투 밀어내기로 해석한다. 격리는 벽이 담당하고, 바깥 사각 clamp는 탈출 방지 그물.
 *
 * ⚠️ 프론트 game/prisonLayout.ts(WALL_BOXES·BOUND) 와 반드시 동일해야 한다
 *    (서버 권위 + 클라 예측이 어긋나면 러버밴딩). 한쪽 바꾸면 양쪽 반영.
 *
 * 레이아웃: 식당 — 서통로 — [감방블록+복도] — 동통로 — 운동장.
 */
final class Collision {

    static final double PLAYER_R = 0.4;
    static final double BOUND_X = 44.2; // 바깥 안전 경계
    static final double BOUND_Z = 14.2;

    private record Box(double cx, double cz, double hx, double hz) {}

    // 감방문: id가 solved에 있으면(열림) 충돌에서 제외 → 통과. 프론트 prisonLayout.DOOR_BOXES 와 일치.
    private record DoorBox(String id, double cx, double cz, double hx, double hz) {}

    private static final DoorBox[] DOORS = {
        new DoorBox("cell-A", -7, 2.5, 1.0, 0.2),
        new DoorBox("cell-B", 7, 2.5, 1.0, 0.2),
        new DoorBox("cell-C", -7, -2.5, 1.0, 0.2),
        new DoorBox("cell-D", 7, -2.5, 1.0, 0.2),
    };

    // prisonLayout.WALL_BOXES 와 동일한 28개(솔리드 벽 22 + 창살 6).
    private static final Box[] WALLS = {
        // 감방블록 외벽
        new Box(0.0, 11.0, 14.2, 0.2),
        new Box(0.0, -11.0, 14.2, 0.2),
        new Box(14.0, 6.85, 0.2, 4.35),
        new Box(14.0, -6.85, 0.2, 4.35),
        new Box(-14.0, 6.85, 0.2, 4.35),
        new Box(-14.0, -6.85, 0.2, 4.35),
        // 감방 사이 세로 벽
        new Box(0.0, 6.65, 0.2, 4.15),
        new Box(0.0, -6.65, 0.2, 4.15),
        // 동통로
        new Box(20.1, 2.5, 6.1, 0.2),
        new Box(20.1, -2.5, 6.1, 0.2),
        // 서통로
        new Box(-20.1, 2.5, 6.1, 0.2),
        new Box(-20.1, -2.5, 6.1, 0.2),
        // 운동장 담
        new Box(35.1, 14.0, 9.1, 0.2),
        new Box(35.1, -14.0, 9.1, 0.2),
        new Box(44.0, 0.0, 0.2, 14.2),
        new Box(26.0, 8.35, 0.2, 5.85),
        new Box(26.0, -8.35, 0.2, 5.85),
        // 식당 벽
        new Box(-35.1, 12.0, 9.1, 0.2),
        new Box(-35.1, -12.0, 9.1, 0.2),
        new Box(-44.0, 0.0, 0.2, 12.2),
        new Box(-26.0, 7.35, 0.2, 4.85),
        new Box(-26.0, -7.35, 0.2, 4.85),
        // 감방 정면 창살(문 개구부 제외)
        new Box(-10.9, 2.5, 2.9, 0.2),
        new Box(0.0, 2.5, 6.0, 0.2),
        new Box(10.9, 2.5, 2.9, 0.2),
        new Box(-10.9, -2.5, 2.9, 0.2),
        new Box(0.0, -2.5, 6.0, 0.2),
        new Box(10.9, -2.5, 2.9, 0.2),
    };

    private Collision() {}

    /**
     * (x,z)를 바깥 경계 + 벽 밖으로 밀어낸 위치를 반환.
     * 감방문은 openDoors에 id가 있으면(열림) 충돌에서 제외 → 통과.
     */
    static double[] resolve(double x, double z, Set<String> openDoors) {
        x = clamp(x, -BOUND_X, BOUND_X);
        z = clamp(z, -BOUND_Z, BOUND_Z);

        final double r = PLAYER_R;
        double[] p = {x, z};
        for (Box b : WALLS) {
            pushOut(p, b.cx(), b.cz(), b.hx(), b.hz(), r);
        }
        for (DoorBox d : DOORS) {
            if (openDoors.contains(d.id())) {
                continue; // 열린 문은 통과
            }
            pushOut(p, d.cx(), d.cz(), d.hx(), d.hz(), r);
        }
        return p;
    }

    /**
     * (x,z)가 <b>벽</b>에 막히면 true. 감방문은 보지 않는다 — 봇 길찾기 전용.
     *
     * 봇에게 감방문은 장애물이 아니라 "열고 지나갈 것"이다(Room.tick이 근접하면 열어준다).
     * 문까지 막힌 것으로 세면 문 좌표 자체가 통행 불가가 되어, 문을 웨이포인트로 삼는 시야 검사가
     * 항상 실패한다 → 봇이 감방 안에서 제자리만 맴돈다(2026-07-18 실측: 178m를 움직였는데
     * 순 이동은 0이었다).
     */
    static boolean blockedByWall(double x, double z) {
        if (x < -BOUND_X || x > BOUND_X || z < -BOUND_Z || z > BOUND_Z) {
            return true;
        }
        double[] p = {x, z};
        for (Box b : WALLS) {
            pushOut(p, b.cx(), b.cz(), b.hx(), b.hz(), PLAYER_R);
        }
        return Math.abs(p[0] - x) > 1e-9 || Math.abs(p[1] - z) > 1e-9;
    }

    /**
     * (x,z)에서 range 안에 있는 <b>닫힌</b> 감방문 중 최근접의 id. 없으면 null.
     *
     * 봇이 스스로 문을 열 때 쓴다. 사람은 프론트에서 근접 판정 후 F로 여는데, 봇은 프론트가
     * 없으니 서버가 같은 판정을 대신한다(사거리도 프론트 DOOR_RANGE와 같은 값을 쓸 것).
     */
    static String nearestClosedDoor(double x, double z, Set<String> openDoors, double range) {
        String best = null;
        double bestD2 = range * range;
        for (DoorBox d : DOORS) {
            if (openDoors.contains(d.id())) {
                continue;
            }
            double dx = d.cx() - x;
            double dz = d.cz() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = d.id();
            }
        }
        return best;
    }

    /** 원(반경 r)을 AABB 박스 밖으로 밀어낸다. p={x,z}를 제자리 수정. */
    private static void pushOut(double[] p, double cx, double cz, double hx, double hz, double r) {
        double x = p[0];
        double z = p[1];
        double nx = clamp(x, cx - hx, cx + hx);
        double nz = clamp(z, cz - hz, cz + hz);
        double dx = x - nx;
        double dz = z - nz;
        double d2 = dx * dx + dz * dz;
        if (d2 >= r * r) {
            return;
        }
        if (d2 > 1e-8) {
            double d = Math.sqrt(d2);
            double push = (r - d) / d;
            p[0] = x + dx * push;
            p[1] = z + dz * push;
        } else {
            double penX = hx + r - Math.abs(x - cx);
            double penZ = hz + r - Math.abs(z - cz);
            if (penX < penZ) {
                p[0] = x + Math.signum(x - cx) * penX;
            } else {
                p[1] = z + Math.signum(z - cz) * penZ;
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
