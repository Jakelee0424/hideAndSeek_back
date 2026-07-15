package com.game3d.server.game;

import java.util.List;
import java.util.Set;

/**
 * 봇 목표 지점용 퍼즐 레지스트리(XZ 평면). 충돌용 박스와 달리 "어디로 갈지"만 담는다.
 *
 * ⚠️ 프론트 game/interactables.ts의 INTERACTABLES와 id·좌표가 반드시 일치해야 한다.
 *    한쪽 바꾸면 양쪽 반영. 코드 자물쇠가 있는 것만 담는다(note-1은 힌트 전용 → 해결 대상 아님).
 */
final class Puzzles {

    record Puzzle(String id, double x, double z) {}

    private static final List<Puzzle> ALL = List.of(
            new Puzzle("lockbox-1", 5, -4),
            new Puzzle("door-1", -8, 0)
    );

    private Puzzles() {}

    /** id로 조회. 없으면 null. */
    static Puzzle find(String id) {
        for (Puzzle p : ALL) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 아직 안 풀렸고 excludeId도 아닌 퍼즐 중 (x,z)에서 최근접. 후보가 없으면 null. */
    static Puzzle nearestUnsolved(double x, double z, Set<String> solved, String excludeId) {
        Puzzle best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Puzzle p : ALL) {
            if (solved.contains(p.id()) || p.id().equals(excludeId)) {
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
