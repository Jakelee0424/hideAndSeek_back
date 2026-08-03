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
 *   남쪽 절반은 연병장 개활지 — 남벽 중앙이 파란 정문(닫힌 함정, gate-lock을 풀면 열린다). 진짜 출구는 북벽 세탁실 뒤 배수관 해치(pipe-hatch).
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

    // 계단(복도 서쪽 끝 중앙, 막다른 벽을 향해 오르는 직선 계단). 동쪽 끝(X1)이 1층,
    // 서쪽 끝(X0)이 2층 — 꼭대기 랜딩에서 좌우(남·북) 테라스로 갈라진다.
    // 양측 난간벽이 옆 진입을 막고, 1층 통행은 계단 남/북의 2m 통로로 지나간다.
    private static final double STAIR_X0 = -36;
    private static final double STAIR_X1 = -29.6;
    private static final double STAIR_Z0 = 16;
    private static final double STAIR_Z1 = 18;

    private record Rect(double x0, double z0, double x1, double z1) {}

    /** 2층 바닥 슬래브: 감방 두 열 위 + 테라스형 복도(난간) + 계단 상단 랜딩(서쪽 끝).
     *  복도 가운데(z 16~18)의 계단 동쪽은 아트리움 개구부 — 테라스에서 1층이 내려다보인다. */
    private static final Rect[] SLAB2 = {
        new Rect(-38, 20, -6, 28),
        new Rect(-38, STAIR_Z1, -6, 20),
        new Rect(-38, 6, -6, 14),
        new Rect(-38, 14, -6, STAIR_Z0),
        new Rect(-38, STAIR_Z0, STAIR_X0, STAIR_Z1),
    };

    /** 별관·화장실 평지붕 높이. 벽을 여기까지 올렸다(프론트 prisonLayout.ANNEX_H와 같은 값). */
    static final double ANNEX_H = 4.5;

    /**
     * 지붕 슬래브(별관 + 화장실) — 프론트 prisonLayout.ROOF_SLABS와 같은 값.
     *
     * ⚠️ {@link #SLAB2}와 <b>따로</b> 둔다. SLAB2는 {@link #onSlab2}를 통해 봇 길찾기 격자의
     * 2층 레이어로도 쓰이는데, 지붕은 올라갈 길이 없어 격자에 넣으면 고립된 섬이 된다
     * (부팅 도달성 리포트의 2층 비율만 망가뜨린다). 여기서 정하는 건 "밟으면 딛는 바닥"뿐이다.
     *
     * 지붕이 렌더에만 있고 이 목록에 없으면, 지붕 위에 선 순간 딛을 바닥이 없어 <b>그대로
     * 잠긴 방 안으로 떨어진다</b>. 지금은 2층에서 지붕으로 나갈 길이 없지만, 수감동 동벽이나
     * 2층 막이를 건드리는 순간 그 구멍이 열린다.
     */
    private static final Rect[] ROOF_SLABS = {
        new Rect(6, 6, 38, 28),   // 별관(식당·세탁실·작업장·의무실 + 별관 복도)
        new Rect(-6, 20, 6, 28),  // 화장실
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
        // 식당(동쪽 1/4은 조리실로 분리): 식탁 6(서편 2열×3행) + 조리실 분리벽(x18, 북끝 출입구만 개방) + 조리실 냉장고·조리대.
        new Obst(8.5, 22, 1.0, 0.45, -1, 3), new Obst(11.5, 22, 1.0, 0.45, -1, 3),
        new Obst(8.5, 24, 1.0, 0.45, -1, 3), new Obst(11.5, 24, 1.0, 0.45, -1, 3),
        new Obst(8.5, 26, 1.0, 0.45, -1, 3), new Obst(11.5, 26, 1.0, 0.45, -1, 3),
        new Obst(18, 23.2, 0.2, 3.2, -1, 3),   // 조리실 분리벽(z20~26.4; 북끝은 출입구)
        new Obst(20.6, 27.4, 0.6, 0.5, -1, 3), // 조리실 냉장고
        new Obst(21.3, 23, 0.45, 1.6, -1, 3),  // 조리실 조리대(동벽)
        // 세탁실: 세탁기 4대(북벽) + 카트(동남쪽 구석 — 문(x30) 정면 동선을 비운다)
        new Obst(25, 26.8, 0.8, 0.9, -1, 3), new Obst(28.2, 26.8, 0.8, 0.9, -1, 3),
        new Obst(31.4, 26.8, 0.8, 0.9, -1, 3), new Obst(34.6, 26.8, 0.8, 0.9, -1, 3),
        new Obst(35, 21.6, 0.7, 0.5, -1, 3),
        // 작업장: 작업대 6개(3열×2행 [9.5·14·18.5]×[11.5·7.5])는 뚫고 못 지나가게 충돌.
        //   나머지 소품은 시각 전용. 프론트 prisonLayout.OBSTACLES / PrisonProps.WORKBENCHES와 같은 자리.
        new Obst(9.5, 11.5, 0.9, 0.45, -1, 3), new Obst(14, 11.5, 0.9, 0.45, -1, 3), new Obst(18.5, 11.5, 0.9, 0.45, -1, 3),
        new Obst(9.5, 7.5, 0.9, 0.45, -1, 3), new Obst(14, 7.5, 0.9, 0.45, -1, 3), new Obst(18.5, 7.5, 0.9, 0.45, -1, 3),
        // 의무실: 침대 3 + 약장(동벽)
        new Obst(25.5, 8.3, 0.6, 1.3, -1, 3), new Obst(30, 8.3, 0.6, 1.3, -1, 3),
        new Obst(34.5, 8.3, 0.6, 1.3, -1, 3), new Obst(36.8, 10, 0.5, 1.5, -1, 3),
        // 연병장(황량한 마당): 남서 구석의 벤치 셋 + 농구골대 기둥
        new Obst(-37, -29.2, 2, 0.35, -1, 3), new Obst(-31, -29.2, 2, 0.35, -1, 3),
        new Obst(-41.3, -25, 0.35, 2, -1, 3),
        new Obst(7.5, -12, 0.15, 0.15, -1, 3),
        // 세탁실 뒤 배수관(북벽, 최종 탈출구): 헤드월+관 입구 구조물(관통 방지). 프론트 OBSTACLES와 같은 값.
        new Obst(30, 29.75, 2.2, 0.25, -1, 3),
        // 배수관 우회 차단: 북쪽 순찰로(z28~30)를 서편에서 돌아오는 길을 세탁실 서벽 연장선(x22)에서 막는다.
        //   → 배수관 구역은 동쪽 샛길 철창(gate-drain, 표식 4개)으로만 들어간다. 프론트 OBSTACLES와 같은 값.
        new Obst(22, 29, 0.2, 1, -1, 3),
        // 정문 기둥
        new Obst(-4.5, -30, 0.5, 0.5, -1, 3), new Obst(4.5, -30, 0.5, 0.5, -1, 3),
        // 수감동↔복도 철창 게이트(x=-6 개구부 z14~20): 가운데 2m(z16~18)만 문(gate-cellblock)이고
        //   나머지(z14~16 · z18~20)는 상시 철창벽. E로 문을 여닫는다. 프론트 OBSTACLES와 같은 값.
        new Obst(-6, 15, 0.1, 1, -1, 3), new Obst(-6, 19, 0.1, 1, -1, 3),
        // 감시탑 안쪽 다리
        new Obst(-40.8, -28.8, 0.15, 0.15, -1, 3), new Obst(40.8, -28.8, 0.15, 0.15, -1, 3),
        new Obst(-40.8, 28.8, 0.15, 0.15, -1, 3), new Obst(40.8, 28.8, 0.15, 0.15, -1, 3),
        // 계단 구조물(복도 서쪽 끝 중앙 계단, 프론트 주석 참고): 양측 난간벽(전 높이)
        // · 계단 밑 진입 차단 · 2층 테라스 난간(아트리움 가장자리) · 2층 복도 동측 막이
        new Obst((STAIR_X0 + STAIR_X1) / 2, STAIR_Z0, (STAIR_X1 - STAIR_X0) / 2, 0.1, -1, 99),
        new Obst((STAIR_X0 + STAIR_X1) / 2, STAIR_Z1, (STAIR_X1 - STAIR_X0) / 2, 0.1, -1, 99),
        new Obst(-31.95, 17, 1.21, 0.9, -1, 0.4),
        new Obst((STAIR_X1 - 6) / 2, STAIR_Z0, (-6 - STAIR_X1) / 2, 0.1, 3, 99),
        new Obst((STAIR_X1 - 6) / 2, STAIR_Z1, (-6 - STAIR_X1) / 2, 0.1, 3, 99),
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
        // 외벽. 남벽 중앙: 닫힌 정문(gate-main) — 함정이다. 정문 자물쇠(gate-lock)를 풀면 열리고
        // 그 순간 Room이 무작위 2명을 재수감한다. 북벽 세탁실 뒤: 배수관 해치(pipe-hatch) —
        // 진짜 최종 탈출구. 배수관 코드(escape-pipe)를 풀면 열린다.
        new Bldg(-42, -30, 42, 30, new Opening('S', 0, 8, "gate-main"), new Opening('N', 30, 3, "pipe-hatch")),
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
        new Bldg(6, 20, 22, 28, new Opening('S', 14, 4, "door-cafe")),       // 식당(요일 코드 lock-cafe로 입장)
        new Bldg(22, 20, 38, 28, new Opening('S', 30, 2, "door-laundry"), new Opening('W', 24, 8, null),
                new Opening('N', 22, 0.4, null), new Opening('S', 22, 0.4, null)),
        new Bldg(6, 6, 22, 14, new Opening('N', 14, 2, "door-work")),        // 작업장(볼트-너트 잠금 lock-work → door-work)
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
        // 배수관 샛길 철창(표식 게이트): 동쪽 샛길(건물 x38 ~ 외벽 x42)을 z=26에서 가로막는 자립 문.
        // 방 스펙(벽 개구부)이 아니라 자립 장벽이라 여기서 직접 추가한다. Room이 표식 4개면 openDoors에
        // "gate-drain"을 넣어 통과시킨다. ⚠️ 프론트 prisonLayout.DRAIN_GATE와 좌표·id를 맞춘다.
        out.add(new DoorBox("gate-drain", 40, 26, 2, 0.25));
        // 수감동↔복도 철창 게이트의 작은 문(x=-6, z16~18, 폭 2m). 상시 개폐(E) — Room.toggleDoor가
        // openDoors에 "gate-cellblock"을 넣고 빼며, 없으면(닫힘) 충돌로 막는다. 양옆 철창벽은 위 OBSTACLES.
        // ⚠️ 프론트 prisonLayout.CELLBLOCK_GATE와 좌표·id를 맞춘다.
        out.add(new DoorBox("gate-cellblock", -6, 17, 0.1, 1));
        // 건물 출입구(화장실 맞은편) 철창 슬라이딩 게이트(x=0, z14, 폭 3m). 상시 개폐(E) — Room.toggleDoor.
        // ⚠️ 프론트 prisonLayout.ENTRANCE_GATE와 좌표·id를 맞춘다.
        out.add(new DoorBox("gate-entrance", 0, 14, 1.5, 0.2));
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
        // 별관·화장실 지붕도 딛을 수 있는 바닥이다(2층과 같은 높이라 위 분기와 겹치지 않는다).
        if (g == 0 && ANNEX_H <= feetY + STEP_UP) {
            for (Rect s : ROOF_SLABS) {
                if (x >= s.x0() && x <= s.x1() && z >= s.z0() && z <= s.z1()) {
                    g = ANNEX_H;
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
     * 소품은 본다 — 1층 소품(침대·테이블·계단 난간벽)을 피해 걸어야 한다. 안 그러면 직선 시야가
     * 소품을 관통해 봇이 소품 앞에 붙어 정지한다.
     *
     * <p>발높이를 안 주면 1층(0)으로 본다 — 순찰 간수는 늘 1층이라 그대로 쓰면 된다.
     */
    static boolean blockedByWall(double x, double z) {
        return blockedByWall(x, z, 0);
    }

    /**
     * 발높이 feetY에서 (x,z)가 막히는가. 소품은 그 높이에서 유효한 것만 본다 —
     * 1층 침대는 2층 통행을 막지 않고, 2층 난간은 1층을 막지 않는다({@link Obst}의 [y0,y1)).
     *
     * ⚠️ 2026-08-01 이전엔 발높이가 0으로 박혀 있었다("봇은 늘 1층"). 격자 길찾기({@link Nav})가
     * 2층 레이어를 구우려면 층별 판정이 반드시 필요하다.
     */
    static boolean blockedByWall(double x, double z, double feetY) {
        if (x < -BOUND_X || x > BOUND_X || z < -BOUND_Z || z > BOUND_Z) {
            return true;
        }
        double[] p = {x, z};
        for (Box b : WALLS) {
            pushOut(p, b.cx(), b.cz(), b.hx(), b.hz(), PLAYER_R);
        }
        for (Obst o : OBSTACLES) {
            if (feetY >= o.y0() && feetY < o.y1()) {
                pushOut(p, o.cx(), o.cz(), o.hx(), o.hz(), PLAYER_R);
            }
        }
        return Math.abs(p[0] - x) > 1e-9 || Math.abs(p[1] - z) > 1e-9;
    }

    /**
     * (x,z)의 <b>계단 램프 높이</b>. 계단 사각형 밖이면 0(1층 바닥).
     *
     * {@link #groundHeight}는 "지금 발높이에서 STEP_UP 안에 닿는 바닥"이라 램프 중간 높이를
     * 못 준다(1층에 선 채로는 램프 아랫부분만 보인다). 격자를 구울 때는 지금 어디 서 있는지와
     * 무관하게 그 칸의 높이가 필요해서 따로 둔다.
     */
    static double rampHeight(double x, double z) {
        if (x >= STAIR_X0 && x <= STAIR_X1 && z >= STAIR_Z0 && z <= STAIR_Z1) {
            return FLOOR2_Y * (STAIR_X1 - x) / (STAIR_X1 - STAIR_X0);
        }
        return 0;
    }

    /** (x,z)가 2층 슬래브 위인가(그 칸에 2층 바닥이 있는가). */
    static boolean onSlab2(double x, double z) {
        for (Rect s : SLAB2) {
            if (x >= s.x0() && x <= s.x1() && z >= s.z0() && z <= s.z1()) {
                return true;
            }
        }
        return false;
    }

    /** 직선 시야 판정의 표본 간격(m). */
    private static final double LOS_STEP = 0.5;

    /**
     * 두 점을 잇는 직선이 뚫려 있는가. 표본을 찍어 {@link #blockedByWall}에 걸리는지 본다.
     *
     * 문은 장애물로 세지 않는다 — 열린 문틈으로 보이는 게 맞고, 닫힌 문까지 벽으로 세면
     * 문 좌표가 통행 불가가 되어 봇 길찾기가 무너진다({@link Nav} 주석 참고).
     *
     * <p>봇 길찾기({@link Nav})와 순찰 간수 시야({@link Patrol})가 함께 쓴다 —
     * 같은 판정을 두 벌 두면 한쪽만 고쳐져 어긋난다.
     */
    static boolean lineClear(double x1, double z1, double x2, double z2) {
        return lineClear(x1, z1, x2, z2, 0);
    }

    /** 발높이 feetY 기준 직선 시야. 두 점이 같은 층일 때만 의미가 있다. */
    static boolean lineClear(double x1, double z1, double x2, double z2, double feetY) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        int steps = (int) Math.ceil(len / LOS_STEP);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            if (blockedByWall(x1 + dx * t, z1 + dz * t, feetY)) {
                return false;
            }
        }
        return true;
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
