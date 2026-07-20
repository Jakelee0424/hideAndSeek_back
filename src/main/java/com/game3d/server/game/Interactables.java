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

    /**
     * label은 LLM 프롬프트에 그대로 들어간다. 토큰 예산이 빠듯하니 짧게 유지할 것.
     *
     * botSolvable=true면 봇이 그 앞에 잠시 머문 뒤 푼 것으로 친다(퍼즐 UI가 없으니 대신).
     * 감방 자물쇠가 이것이다 — 봇이 갇힌 방의 자물쇠는 문이 잠겨 밖에서도 닿을 수 없어서,
     * 봇이 스스로 못 풀면 영영 갇힌다. 자세한 건 {@link Room#solveNearbyForBot}.
     */
    record Poi(String id, double x, double z, boolean solvable, boolean botSolvable, String label) {}

    // 좌표는 프론트 interactables.ts의 position [x, y, z]에서 x·z만 가져온 것이다.
    // 2026-07-18 감옥 재구성 때 프론트만 갱신되고 여기가 옛 좌표(5,-4 / -8,0 / 0,6)로 남아
    // 봇이 유령 지점으로 걸어가다 벽에 박혀 정지했다. note-1의 옛 좌표 (0,6)은 감방 사이
    // 세로 벽(Collision의 Box(0, 6.65, 0.2, 4.15)) 안이었다.
    // 2026-07-19 방탈출 미션 개편(eec8f82)에서 같은 일이 또 났다 — 프론트가 12개로 늘었는데
    // 여기는 옛 3개(lockbox-1/door-1/note-1) 그대로였다.
    private static final List<Poi> ALL = List.of(
            // 감방 자물쇠 4개. 풀면 그 방 감방문이 열린다(Room.LOCK_OPENS).
            // 사람은 여기서 아케이드 미니게임을 한 판 이겨야 한다. 어떤 게임이 걸리는지는
            // 프론트가 방 코드로 정하므로 서버는 모른다 — 봇에게도 알릴 게 없어 라벨에서 뺐다.
            new Poi("lock-A", -7, 3.6, true, true, "게임 자물쇠(1호실)"),
            new Poi("lock-B", 7, 3.6, true, true, "게임 자물쇠(2호실)"),
            new Poi("lock-C", -7, -3.6, true, true, "게임 자물쇠(3호실)"),
            new Poi("lock-D", 7, -3.6, true, true, "게임 자물쇠(4호실)"),
            // 감방 안 쪽지 8개는 없앴다(2026-07-20). 자물쇠가 미니게임이 되면서 알려줄 답이
            // 사라졌기 때문이다. 프론트 interactables.ts에서도 함께 지웠다 — 한쪽만 지우면
            // 봇이 유령 좌표로 걸어가 벽에 박힌다(이 파일 위쪽 주석의 전례 참고).
            // 최종 탈옥문과 그 단서. 단서는 감방 밖이라 누구나 닿는다.
            // 탈옥문은 botSolvable=false — 봇이 열면 봇이 게임을 끝내 버린다.
            new Poi("note-mess", -35, 7, false, false, "배식 당번표(식당)"),
            new Poi("note-west", -20, 1.5, false, false, "순찰 일지(서통로)"),
            new Poi("note-yard", 38, -8, false, false, "담벼락 자국(운동장)"),
            new Poi("escape-gate", 42, 0, true, false, "탈옥문(운동장, 최종 탈출구)")
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

    /**
     * 아직 안 풀렸고 exclude에도 없는 <b>해결 가능한</b> 지점 중 (x,z)에서 최근접. 후보가 없으면 null.
     *
     * exclude에 "직전 한 곳"이 아니라 "다녀온 곳 전부"를 넘겨야 한다. 해결 가능한 지점이 둘뿐인데
     * 직전만 빼면 남는 건 항상 반대쪽 하나 → 두 곳을 영원히 왕복한다(2026-07 실측).
     */
    /**
     * exclude에 없는 <b>쪽지</b>(solvable=false) 중 (x,z)에서 최근접. 후보가 없으면 null.
     *
     * 자물쇠가 다 풀리고 나면 해결할 게 없어져 봇이 사람만 졸졸 따라다닌다. 그때 읽을 쪽지를
     * 주려는 것이다. 호출부가 "다녀온 곳"을 exclude로 넘기므로 같은 쪽지를 반복해 고르지 않는다.
     */
    static Poi nearestUnvisitedNote(double x, double z, Set<String> exclude) {
        Poi best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Poi p : ALL) {
            if (p.solvable() || exclude.contains(p.id())) {
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

    static Poi nearestUnsolved(double x, double z, Set<String> solved, Set<String> exclude) {
        Poi best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Poi p : ALL) {
            if (!p.solvable() || solved.contains(p.id()) || exclude.contains(p.id())) {
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
