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
    // 지금은 캠퍼스 배치(수감동·별관·연병장). 봇이 목표로 삼는 것만 담는다 — 감방 자물쇠 + 탈옥 단서·탈옥문.
    private static final List<Poi> ALL = List.of(
            // 감방 자물쇠 4개. 풀면 그 방 문이 열린다(Room.LOCK_OPENS).
            // 사람은 아케이드 미니게임을 한 판 이겨야 한다 — 어떤 게임인지는 프론트가 방 코드로
            // 정하므로 서버는 모른다(봇에게도 알릴 게 없어 라벨에서 뺐다). 좌표는 도면 배치.
            new Poi("lock-A", -30, 21, true, true, "게임 자물쇠(1-1)"),
            new Poi("lock-B", -14, 21, true, true, "게임 자물쇠(1-2)"),
            new Poi("lock-C", -30, 13, true, true, "게임 자물쇠(1-3)"),
            new Poi("lock-D", -14, 13, true, true, "게임 자물쇠(1-4)"),
            // 감방 안 쪽지는 POI에 넣지 않는다 — 미니게임이라 답이 없어 봇이 읽을 이유가 없다.
            // 프론트엔 분위기용 쪽지가 남아 있지만 봇은 목표로 삼지 않는다(front-has-more는 안전).
            //
            // 봇이 순회할 쪽지: 별관 복도의 방 자물쇠 힌트 쪽지(항상 열린 복도라 봇이 닿는다).
            // 잠긴 방 "안"의 쪽지는 넣지 않는다 — 넣으면 봇이 닫힌 문 앞으로 걸어가 멈춘다.
            // (해독 조각 문서 doc-cafe/hall/yard는 옛 "표식+수 셈법" 시스템과 함께 없앴다.)
            // 2026-07-27: 쪽지가 전부 별관 복도 두 줄에 제 자물쇠 4.5m 안으로 몰려 있어
            // 탐색·공유가 안 일어났다. 네 구역으로 흩었다(프론트 interactables.ts와 같은 좌표).
            // ⚠️ note-med1은 프론트가 (3, 22) 화장실 쪽 개방 구역으로 옮겼는데 여기가 옛 좌표
            //    (10.5, 22)로 남아 있었다 — 지금 그 자리는 **요일 코드로 잠긴 식당 안**이라,
            //    봇이 못 여는 문 앞으로 걸어가 멈추는 그 함정(escape-pipe 때와 같은 종류)이었다.
            //    2026-07-31 프론트 좌표로 맞췄다.
            new Poi("note-med1", 3, 22, false, false, "약장 라벨(화장실)"),
            new Poi("note-laundry1", 2, 24.5, false, false, "세탁 안내문(화장실)"),
            new Poi("note-laundry2", 24, -10, false, false, "젖은 쪽지(연병장 동편)"),
            new Poi("note-med2", -24, -18, false, false, "처방 기록(연병장 서편)"),
            // 감시탑 각인(정문 코드 힌트). 연병장 개활지라 직선으로 닿는다 —
            // 해독 문서 3곳이 빠지면서 봇이 순회할 쪽지가 둘뿐이라 순회가 너무 단조로워졌다.
            new Poi("gate-note1", -9, -27, false, false, "서쪽 감시탑 각인(연병장)"),
            new Poi("gate-note2", 9, -27, false, false, "동쪽 감시탑 각인(연병장)")
            // 최종 탈출구(배수관)와 정문 자물쇠는 POI에 없다.
            //
            // 정문은 출구가 아니라 함정이라 뺐다(봇이 함정으로 걸어가면 안 된다).
            // 배수관(escape-pipe, 30/29)은 2026-07-27에 뺐다 — 봇이 한 판 내내 세탁실 문 앞에
            // 붙어 서 있던 원인이었다. botSolvable=false라 풀지도 못하는데 solvable=true여서
            // nearestUnsolved가 계속 골랐고, 그 좌표의 최근접 웨이포인트가 하필 **잠긴 세탁실
            // 안**(BotNav 노드 10)이라 봇이 문 앞에 멈춘 채 판이 끝났다. 자기 감방을 푼 뒤엔
            // 남은 solvable POI가 이것뿐이라 매 판 재현됐다(실측 110초 정지 — 이 결함이 있기
            // 전 코드로도 재현해, 그날의 다른 작업과 무관한 기존 문제임을 확인했다).
            // 이제 풀 게 없으면 nearestUnsolved가 null을 주고 봇은 쪽지 순회로 넘어간다.
            // 마지막이 AI 지목 투표라, 한 판 내내 안 움직이는 놈이 있으면 정체가 드러난다.
            // ⚠️ 되살리려면 배수관까지 가는 웨이포인트(별관을 돌아 북쪽 순찰로)부터 놓을 것.
            //    지금 그래프로는 잠긴 방을 통과하는 경로밖에 안 나온다.
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

    /**
     * 아직 안 풀렸고 exclude에도 없는 <b>해결 가능한</b> 지점 중 (x,z)에서 최근접. 후보가 없으면 null.
     *
     * exclude에 "직전 한 곳"이 아니라 "다녀온 곳 전부"를 넘겨야 한다. 해결 가능한 지점이 둘뿐인데
     * 직전만 빼면 남는 건 항상 반대쪽 하나 → 두 곳을 영원히 왕복한다(2026-07 실측).
     */
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
