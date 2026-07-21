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
 * 레이아웃(도면, 84×60 가로 직사각형): 북쪽에 수감동(감방 4개가 복도를 사이에 두고 2:2 마주보기, 서)과
 *   별관(식당·세탁실·작업장·의무실, 동)이 가운데 복도로 이어지고(중간에 화장실·열린 철창),
 *   남쪽 절반은 연병장 개활지 — 남벽 중앙이 파란 정문(gate-main, 탈옥문을 풀면 열린다).
 *   전체를 외벽이 감싼다. (연병장은 벽 없는 바닥이라 여기 없다.)
 *
 * 인접한 방이 벽을 공유할 때는 한쪽만 벽을 갖고 반대쪽은 그 변 전체를 개구부로 비운다
 * (프론트가 같은 자리에 벽을 두 번 그리면 깜빡여서 생긴 규칙 — 충돌도 같은 스펙을 쓴다).
 */
final class Collision {

    static final double PLAYER_R = 0.4;
    static final double WALL_T = 0.4;
    static final double WALL_H = 3;    // 실내 벽·잠금 문 높이(문 층 판정에 쓴다)
    static final double BOUND_X = 41.6; // 외벽 안쪽(탈출 방지 그물)
    static final double BOUND_Z = 29.6;

    // ── 수감동 2층(프론트 prisonLayout.FLOOR2_Y·STEP_UP·STAIR·SLAB2와 같은 값) ──
    static final double FLOOR2_Y = 4.5; // 2층 바닥 높이(발바닥 기준)
    static final double STEP_UP = 0.5;  // 걸어서 오를 수 있는 턱(계단은 이 스냅으로 오른다)

    // 계단(수감동 복도 중앙, 북벽에 붙은 직선 계단). 동쪽 끝(X1)이 1층, 서쪽 끝(X0)이 2층.
    private static final double STAIR_X0 = -25.2;
    private static final double STAIR_X1 = -18.8;
    private static final double STAIR_Z0 = 18.6;
    private static final double STAIR_Z1 = 20;

    private record Rect(double x0, double z0, double x1, double z1) {}

    /** 2층 바닥 슬래브(감방 두 열 + 복도, 계단 개구부 제외). */
    private static final Rect[] SLAB2 = {
        new Rect(-38, 20, -6, 28),
        new Rect(-38, 6, -6, 14),
        new Rect(-38, 14, -6, STAIR_Z0),
        new Rect(-38, STAIR_Z0, STAIR_X0, 20),
        new Rect(STAIR_X1, STAIR_Z0, -6, 20),
    };

    private record Box(double cx, double cz, double hx, double hz) {}

    /**
     * 소품 충돌(실체가 있는 오브젝트). 프론트 prisonLayout.OBSTACLES와 같은 값.
     * [y0, y1)은 유효한 발높이 구간 — 1층 침대는 2층 통행을 막지 않고, 2층 난간은 1층을 막지 않는다.
     * 자물쇠·쪽지(상호작용 오브젝트)는 실체가 없다.
     */
    private record Obst(double cx, double cz, double hx, double hz, double y0, double y1) {}

    private static final Obst[] OBSTACLES = {
        // 감방 소품: 이층 침상(서벽) + 변기(문 반대편 구석). 감방 rect·문 방향에서 딴 좌표.
        new Obst(-36.6, 24, 0.5, 1.55, -1, 3), new Obst(-23.3, 26.7, 0.4, 0.4, -1, 3),   // 1-1
        new Obst(-20.6, 24, 0.5, 1.55, -1, 3), new Obst(-7.3, 26.7, 0.4, 0.4, -1, 3),    // 1-2
        new Obst(-36.6, 10, 0.5, 1.55, -1, 3), new Obst(-23.3, 7.3, 0.4, 0.4, -1, 3),    // 1-3
        new Obst(-20.6, 10, 0.5, 1.55, -1, 3), new Obst(-7.3, 7.3, 0.4, 0.4, -1, 3),     // 1-4
        // 화장실: 변기·칸막이 열(북벽) + 세면대(서벽)
        new Obst(0, 26.8, 4.3, 0.55, -1, 3), new Obst(-5.4, 22.5, 0.35, 1.5, -1, 3),
        // 식당: 식탁+벤치 2조 + 배식대(북벽)
        new Obst(10, 23.5, 1.6, 1.15, -1, 3), new Obst(18, 23.5, 1.6, 1.15, -1, 3),
        new Obst(14, 27.2, 5, 0.5, -1, 3),
        // 세탁실: 세탁기 4대(북벽) + 카트(동남쪽 구석 — 문(x30) 정면 동선을 비운다)
        new Obst(25, 26.8, 0.8, 0.9, -1, 3), new Obst(28.2, 26.8, 0.8, 0.9, -1, 3),
        new Obst(31.4, 26.8, 0.8, 0.9, -1, 3), new Obst(34.6, 26.8, 0.8, 0.9, -1, 3),
        new Obst(35, 21.6, 0.7, 0.5, -1, 3),
        // 작업장: 작업대(북벽 서편 — 문(x14) 정면 동선을 비운다) + 상자 5개
        new Obst(10, 12.4, 3, 0.7, -1, 3),
        new Obst(10, 7.6, 0.5, 0.5, -1, 3), new Obst(12, 7.6, 0.5, 0.5, -1, 3),
        new Obst(14, 7.6, 0.5, 0.5, -1, 3), new Obst(16, 7.6, 0.5, 0.5, -1, 3),
        new Obst(18, 7.6, 0.5, 0.5, -1, 3),
        // 의무실: 침대 3 + 약장(동벽)
        new Obst(25.5, 8.3, 0.6, 1.3, -1, 3), new Obst(30, 8.3, 0.6, 1.3, -1, 3),
        new Obst(34.5, 8.3, 0.6, 1.3, -1, 3), new Obst(36.8, 10, 0.5, 1.5, -1, 3),
        // 연병장: 연단·깃대·벤치 2·농구골대 기둥
        new Obst(-16, 1, 4, 1.3, -1, 3), new Obst(-24, 1, 0.15, 0.15, -1, 3),
        new Obst(-14, -20, 2.5, 0.35, -1, 3), new Obst(-14, -4, 2.5, 0.35, -1, 3),
        new Obst(7.5, -12, 0.15, 0.15, -1, 3),
        // 정문 기둥 + 연결 복도 철창 기둥(x=-3: 출입구 동선(x=0)을 비켜 세운다)
        new Obst(-4.5, -30, 0.5, 0.5, -1, 3), new Obst(4.5, -30, 0.5, 0.5, -1, 3),
        new Obst(-3, 14.5, 0.1, 0.12, -1, 3), new Obst(-3, 19.5, 0.1, 0.12, -1, 3),
        // 감시탑 안쪽 다리
        new Obst(-40.8, -28.8, 0.15, 0.15, -1, 3), new Obst(40.8, -28.8, 0.15, 0.15, -1, 3),
        new Obst(-40.8, 28.8, 0.15, 0.15, -1, 3), new Obst(40.8, 28.8, 0.15, 0.15, -1, 3),
        // 계단 구조물(프론트 주석 참고): 난간벽(전 높이) · 계단 밑 진입 차단 · 2층 난간·막이
        new Obst((STAIR_X0 + STAIR_X1) / 2, STAIR_Z0, (STAIR_X1 - STAIR_X0) / 2, 0.1, -1, 99),
        new Obst(-21.15, 19.3, 1.21, 0.7, -1, 0.4),
        new Obst(STAIR_X1, 19.3, 0.08, 0.7, 3, 99),
        new Obst(-6, 17, 0.2, 3, 3, 99),
    };

    // 잠금 문: id가 solved에 있으면(열림) 충돌에서 제외 → 통과. 프론트 DOOR_BOXES와 일치.
    private record DoorBox(String id, double cx, double cz, double hx, double hz) {}

    // ── 방 스펙(프론트 prisonLayout.BUILDINGS와 동일) ──
    private record Opening(char edge, double at, double width, String door) {}

    private record Bldg(double x0, double z0, double x1, double z1, Opening... ops) {}

    // 프론트 prisonLayout.BUILDINGS와 같은 값(연병장은 벽 없어 제외).
    // door=null인 Opening은 상시 통행 개구부 — 인접 방과 공유하는 변을 통째로 비우는 데도 쓴다.
    private static final Bldg[] BUILDINGS = {
        new Bldg(-42, -30, 42, 30, new Opening('S', 0, 8, "gate-main")),     // 외벽 + 정문(남벽 중앙)
        // 수감동 감방(북서, 2:2 마주보기). 북측(A·B)은 남향 문, 남측(C·D)은 북향 문 → 가운데 복도로.
        // 서쪽 이웃에게 벽을 양보한 방(B·D·세탁실·의무실)은 북/남 변의 공유 모서리 토막(0.4)도
        // 함께 비운다 — 이웃이 모서리 덮개(±t/2)로 이미 채운 자리다(중복 벽 방지).
        new Bldg(-38, 20, -22, 28, new Opening('S', -30, 2, "cell-A")),
        new Bldg(-22, 20, -6, 28, new Opening('S', -14, 2, "cell-B"), new Opening('W', 24, 8, null),
                new Opening('N', -22, 0.4, null), new Opening('S', -22, 0.4, null)),
        new Bldg(-38, 6, -22, 14, new Opening('N', -30, 2, "cell-C")),
        new Bldg(-22, 6, -6, 14, new Opening('N', -14, 2, "cell-D"), new Opening('W', 10, 8, null),
                new Opening('N', -22, 0.4, null), new Opening('S', -22, 0.4, null)),
        // 수감동 복도(북/남 변은 감방 벽이 담당, 동쪽은 연결 복도로 열림 → 서쪽 벽만 남는다)
        new Bldg(-38, 14, -6, 20, new Opening('N', -22, 32.4, null), new Opening('S', -22, 32.4, null),
                new Opening('E', 17, 6, null)),
        // 연결 복도(남벽 중앙이 단지 출입구. 양끝 0.4는 이웃 벽 모서리와 겹치는 토막 제거)
        new Bldg(-6, 14, 6, 20, new Opening('N', 0, 12.4, null), new Opening('E', 17, 6, null),
                new Opening('W', 17, 6, null), new Opening('S', -6, 0.4, null),
                new Opening('S', 0, 3, null), new Opening('S', 6, 0.4, null)),
        // 화장실(연결 복도 북측. 동/서 벽은 이웃 건물이 담당)
        new Bldg(-6, 20, 6, 28, new Opening('W', 24, 8, null), new Opening('E', 24, 8, null),
                new Opening('N', -6, 0.4, null), new Opening('N', 6, 0.4, null),
                new Opening('S', -6, 0.4, null), new Opening('S', 0, 2, null), new Opening('S', 6, 0.4, null)),
        // 별관(북동). 문은 모두 가운데 복도로.
        new Bldg(6, 20, 22, 28, new Opening('S', 14, 4, null)),              // 식당(개방)
        new Bldg(22, 20, 38, 28, new Opening('S', 30, 2, "door-laundry"), new Opening('W', 24, 8, null),
                new Opening('N', 22, 0.4, null), new Opening('S', 22, 0.4, null)),
        new Bldg(6, 6, 22, 14, new Opening('N', 14, 2, "door-work")),        // 작업장
        new Bldg(22, 6, 38, 14, new Opening('N', 30, 2, "door-med"), new Opening('W', 10, 8, null),
                new Opening('N', 22, 0.4, null), new Opening('S', 22, 0.4, null)),
        // 별관 복도(동쪽 벽만 남는다)
        new Bldg(6, 14, 38, 20, new Opening('N', 22, 32.4, null), new Opening('S', 22, 32.4, null),
                new Opening('W', 17, 6, null)),
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
     * (x,z)를 바깥 경계 + 벽 + 소품 밖으로 밀어낸 위치를 반환. feetY는 발바닥 높이(층 판정).
     * 잠금 문은 1층(feetY < WALL_H)에서만 막고, openDoors에 id가 있으면(열림) 충돌에서 제외.
     */
    static double[] resolve(double x, double z, double feetY, Set<String> openDoors) {
        x = clamp(x, -BOUND_X, BOUND_X);
        z = clamp(z, -BOUND_Z, BOUND_Z);

        final double r = PLAYER_R;
        double[] p = {x, z};
        for (Box b : WALLS) {
            pushOut(p, b.cx(), b.cz(), b.hx(), b.hz(), r);
        }
        for (Obst o : OBSTACLES) {
            if (feetY < o.y0() || feetY >= o.y1()) {
                continue; // 다른 층의 소품
            }
            pushOut(p, o.cx(), o.cz(), o.hx(), o.hz(), r);
        }
        if (feetY < WALL_H) {
            for (DoorBox d : DOORS) {
                if (openDoors.contains(d.id())) {
                    continue; // 열린 문은 통과
                }
                pushOut(p, d.cx(), d.cz(), d.hx(), d.hz(), r);
            }
        }
        return p;
    }

    /**
     * (x,z)에서 딛고 설 수 있는 바닥 높이(발바닥 기준). 지금 높이(feetY)에서 STEP_UP 이하로
     * 닿는 바닥 중 가장 높은 것 — 1층에서 2층 슬래브는 머리 위 천장일 뿐이므로 후보에서 빠진다.
     * 프론트 prisonLayout.groundHeightAt과 같은 식.
     */
    static double groundHeight(double x, double z, double feetY) {
        double g = 0;
        if (x >= STAIR_X0 && x <= STAIR_X1 && z >= STAIR_Z0 && z <= STAIR_Z1) {
            double h = FLOOR2_Y * (STAIR_X1 - x) / (STAIR_X1 - STAIR_X0);
            if (h <= feetY + STEP_UP && h > g) {
                g = h;
            }
        } else if (FLOOR2_Y <= feetY + STEP_UP) {
            for (Rect s : SLAB2) {
                if (x >= s.x0() && x <= s.x1() && z >= s.z0() && z <= s.z1()) {
                    g = FLOOR2_Y;
                    break;
                }
            }
        }
        return g;
    }

    /**
     * (x,z)가 <b>벽·소품</b>에 막히면 true. 문은 보지 않는다 — 봇 길찾기 전용.
     *
     * 봇에게 문은 장애물이 아니라 "열고 지나갈 것"이다(Room.tick이 근접하면 열어준다).
     * 문까지 막힌 것으로 세면 문 좌표 자체가 통행 불가가 되어, 문을 웨이포인트로 삼는 시야 검사가
     * 항상 실패한다 → 봇이 방 안에서 제자리만 맴돈다.
     *
     * 소품은 본다 — 봇은 늘 1층(발높이 0)이므로 1층 소품(침대·테이블·계단 난간벽)을 피해 걸어야
     * 한다. 안 그러면 직선 시야가 소품을 관통해 봇이 소품 앞에 붙어 정지한다.
     */
    static boolean blockedByWall(double x, double z) {
        if (x < -BOUND_X || x > BOUND_X || z < -BOUND_Z || z > BOUND_Z) {
            return true;
        }
        double[] p = {x, z};
        for (Box b : WALLS) {
            pushOut(p, b.cx(), b.cz(), b.hx(), b.hz(), PLAYER_R);
        }
        for (Obst o : OBSTACLES) {
            if (o.y0() <= 0 && 0 < o.y1()) { // 1층(발높이 0)에서 유효한 소품만
                pushOut(p, o.cx(), o.cz(), o.hx(), o.hz(), PLAYER_R);
            }
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
