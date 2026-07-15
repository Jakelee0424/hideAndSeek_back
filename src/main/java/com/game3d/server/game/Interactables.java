package com.game3d.server.game;

import java.util.List;
import java.util.Set;

/**
 * 봇이 갈 수 있는 지점(POI) 레지스트리(XZ 평면). 충돌용 박스와 달리 "어디로 갈지"만 담는다.
 *
 * ⚠️ 프론트 game/interactables.ts의 INTERACTABLES와 id·좌표가 반드시 일치해야 한다.
 *    한쪽 바꾸면 양쪽 반영.
 *
 * solvable=false인 지점(쪽지)은 해결 대상이 아니라 힌트를 읽는 곳이다. 영영 solved가 되지 않으므로
 * 목표 선택에서 제외하지 않으면 봇이 거기 눌러앉는다({@link #nearestUnsolved}가 solvable만 보는 이유).
 */
final class Interactables {

    /** label은 LLM 프롬프트에 그대로 들어간다. 토큰 예산이 빠듯하니 짧게 유지할 것. */
    record Poi(String id, double x, double z, boolean solvable, String label) {}

    private static final List<Poi> ALL = List.of(
            new Poi("lockbox-1", 5, -4, true, "자물쇠 상자"),
            new Poi("door-1", -8, 0, true, "잠긴 문(탈출구)"),
            new Poi("note-1", 0, 6, false, "낡은 쪽지(문 코드 힌트)")
    );

    private Interactables() {}

    static List<Poi> all() {
        return ALL;
    }

    /** id로 조회. 없으면 null. */
    static Poi find(String id) {
        for (Poi p : ALL) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 아직 안 풀렸고 excludeId도 아닌 <b>해결 가능한</b> 지점 중 (x,z)에서 최근접. 후보가 없으면 null. */
    static Poi nearestUnsolved(double x, double z, Set<String> solved, String excludeId) {
        Poi best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Poi p : ALL) {
            if (!p.solvable() || solved.contains(p.id()) || p.id().equals(excludeId)) {
                continue;
            }
            double dx = p.x() - x;
            double dz = p.z() - z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
    }
}
