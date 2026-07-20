package com.game3d.server.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 정적 맵 충돌(권위). 플레이어=원(반경 PLAYER_R), 벽=XZ 평면 AABB.
 * 원-AABB 최소침투 밀어내기로 해석한다. 격리는 벽이 담당하고, 바깥 사각 clamp는 탈출 방지 그물.
 *
 * ⚠️ 벽·문은 아래 BUILDINGS(방=사각형 + 문 위치) 스펙에서 <b>자동 생성</b>한다.
 *    프론트 game/prisonLayout.ts의 BUILDINGS와 <b>같은 값</b>이어야 한다(생성 알고리즘도 동일).
 *    방을 옮기면 여기와 prisonLayout.ts의 BUILDINGS만 같이 고치면 벽/문 좌표는 자동으로 맞는다.
 *
 * 레이아웃(도면): 본관 감방(A~D, 북서) — 별관 미션동(식당·세탁실·작업장·의무실, 동) —
 *   남쪽 운동장 개활지 — 교도소 정문. 전체를 외벽이 감싼다. (운동장은 벽 없는 바닥이라 여기 없다.)
 */
final class Collision {

    static final double PLAYER_R = 0.4;
    static final double WALL_T = 0.4;
    static final double BOUND_X = 74.6; // 외벽 안쪽(탈출 방지 그물)
    static final double BOUND_Z = 55.6;

    private record Box(double cx, double cz, double hx, double hz) {}

    // 잠금 문: id가 solved에 있으면(열림) 충돌에서 제외 → 통과. 프론트 DOOR_BOXES와 일치.
    private record DoorBox(String id, double cx, double cz, double hx, double hz) {}

    // ── 방 스펙(프론트 prisonLayout.BUILDINGS와 동일) ──
    private record Opening(char edge, double at, double width, String door) {}

    private record Bldg(double x0, double z0, double x1, double z1, Opening... ops) {}

    // 프론트 prisonLayout.BUILDINGS와 같은 값(운동장은 벽 없어 제외). 문은 남향(감방)·서향(별관).
    private static final Bldg[] BUILDINGS = {
        new Bldg(-75, -56, 75, 56),                                          // 외벽
        // 본관 감방(북서). 문 남향.
        new Bldg(-66, 30, -52, 42, new Opening('S', -59, 2, "cell-A")),
        new Bldg(-50, 30, -36, 42, new Opening('S', -43, 2, "cell-B")),
        new Bldg(-34, 30, -20, 42, new Opening('S', -27, 2, "cell-C")),
        new Bldg(-18, 30, -4, 42, new Opening('S', -11, 2, "cell-D")),
        new Bldg(-66, 44, -52, 54, new Opening('S', -59, 4, null)),          // 관리실(시각)
        new Bldg(-50, 44, -36, 54, new Opening('S', -43, 4, null)),          // 본관 통제실(시각)
        new Bldg(4, 42, 22, 54, new Opening('S', 13, 5, null)),              // 관구실(시각)
        // 별관 미션동(동). 문 서향.
        new Bldg(42, 28, 68, 50, new Opening('W', 39, 6, null)),             // 식당(개방)
        new Bldg(42, 2, 68, 24, new Opening('W', 13, 2, "door-laundry")),    // 세탁실
        new Bldg(42, -24, 68, -2, new Opening('W', -13, 2, "door-work")),    // 작업장
        new Bldg(42, -50, 68, -28, new Opening('W', -39, 2, "door-med")),    // 의무실
        new Bldg(-16, -54, -6, -48, new Opening('N', -11, 3, null)),         // 정문초소(시각)
    };

    private static final Box[] WALLS = buildWalls();
    private static final DoorBox[] DOORS = buildDoors();

    private Collision() {}

    // ── 스펙 → 벽/문 생성(프론트 buildingWalls/buildingDoors와 같은 규약) ──
    private static List<double[]> splitSpan(double lo, double hi, List<double[]> gaps) {
        gaps.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> segs = new ArrayList<>();
        double cur = lo;
        for (double[] g : gaps) {
            if (g[0] > cur) {
                segs.add(new double[] {cur, g[0]});
            }
            cur = Math.max(cur, g[1]);
        }
        if (cur < hi) {
            segs.add(new double[] {cur, hi});
        }
        segs.removeIf(s -> s[1] - s[0] <= 1e-6);
        return segs;
    }

    private static List<double[]> gapsOn(Bldg b, char edge) {
        List<double[]> gaps = new ArrayList<>();
        for (Opening o : b.ops()) {
            if (o.edge() == edge) {
                gaps.add(new double[] {o.at() - o.width() / 2, o.at() + o.width() / 2});
            }
        }
        return gaps;
    }

    private static Box[] buildWalls() {
        double t = WALL_T;
        List<Box> out = new ArrayList<>();
        for (Bldg b : BUILDINGS) {
            // 북/남: 수평(모서리 덮게 x를 t/2씩 확장)
            for (double[] s : splitSpan(b.x0() - t / 2, b.x1() + t / 2, gapsOn(b, 'N'))) {
                out.add(new Box((s[0] + s[1]) / 2, b.z1(), (s[1] - s[0]) / 2, t / 2));
            }
            for (double[] s : splitSpan(b.x0() - t / 2, b.x1() + t / 2, gapsOn(b, 'S'))) {
                out.add(new Box((s[0] + s[1]) / 2, b.z0(), (s[1] - s[0]) / 2, t / 2));
            }
            // 동/서: 수직(모서리는 위가 덮음)
            for (double[] s : splitSpan(b.z0(), b.z1(), gapsOn(b, 'E'))) {
                out.add(new Box(b.x1(), (s[0] + s[1]) / 2, t / 2, (s[1] - s[0]) / 2));
            }
            for (double[] s : splitSpan(b.z0(), b.z1(), gapsOn(b, 'W'))) {
                out.add(new Box(b.x0(), (s[0] + s[1]) / 2, t / 2, (s[1] - s[0]) / 2));
            }
        }
        return out.toArray(new Box[0]);
    }

    private static DoorBox[] buildDoors() {
        double t = WALL_T;
        List<DoorBox> out = new ArrayList<>();
        for (Bldg b : BUILDINGS) {
            for (Opening o : b.ops()) {
                if (o.door() == null) {
                    continue;
                }
                if (o.edge() == 'N' || o.edge() == 'S') {
                    double zc = o.edge() == 'N' ? b.z1() : b.z0();
                    out.add(new DoorBox(o.door(), o.at(), zc, o.width() / 2, t / 2));
                } else {
                    double xc = o.edge() == 'E' ? b.x1() : b.x0();
                    out.add(new DoorBox(o.door(), xc, o.at(), t / 2, o.width() / 2));
                }
            }
        }
        return out.toArray(new DoorBox[0]);
    }

    /**
     * (x,z)를 바깥 경계 + 벽 밖으로 밀어낸 위치를 반환.
     * 잠금 문은 openDoors에 id가 있으면(열림) 충돌에서 제외 → 통과.
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
     * (x,z)가 <b>벽</b>에 막히면 true. 문은 보지 않는다 — 봇 길찾기 전용.
     *
     * 봇에게 문은 장애물이 아니라 "열고 지나갈 것"이다(Room.tick이 근접하면 열어준다).
     * 문까지 막힌 것으로 세면 문 좌표 자체가 통행 불가가 되어, 문을 웨이포인트로 삼는 시야 검사가
     * 항상 실패한다 → 봇이 방 안에서 제자리만 맴돈다.
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
     * (x,z)에서 range 안에 있는 <b>닫힌</b> 문 중 최근접의 id. 없으면 null.
     * 봇이 스스로 문을 열 때 쓴다(사거리도 프론트 DOOR_RANGE와 같은 값).
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
