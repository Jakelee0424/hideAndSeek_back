package com.game3d.server.game;

import java.util.ArrayList;
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
            // (연병장 쪽지 note-laundry2·note-med2는 2026-08-03 사용자 지시로 제거. 프론트 interactables.ts도 동일.)
            // 감시탑 각인(정문 코드 힌트). 연병장 개활지라 직선으로 닿는다.
            new Poi("gate-note1", -9, -27, false, false, "서쪽 감시탑 각인(연병장)"),
            new Poi("gate-note2", 9, -27, false, false, "동쪽 감시탑 각인(연병장)"),
            // ── 별관(2026-08-01 추가) ───────────────────────────────────────────
            // 관찰 결과 봇의 별관 체류가 **0초**였다. POI가 화장실·연병장에만 있어서, 사람들이
            // 방 퍼즐과 씨름하는 동안 봇 혼자 딴 데를 돌았다 — 화면에서 바로 보이는 어색함이다.
            //
            // 복도 쪽은 항상 열려 있어 그냥 넣으면 된다. 방 문 자물쇠는 solvable이지만
            // botSolvable=false다 — 봇은 못 풀고 잠깐 서서 살펴본 뒤 지나간다(사람이 푸는 걸
            // 기다리는 그림). ⚠️ 그 **좌표가 복도**라서 안전하다. 아래 escape-pipe 사건처럼
            // 좌표가 잠긴 방 안이면 봇이 그 문 앞에 박힌다.
            new Poi("lock-cafe", 14, 19.3, true, false, "식당 문 자물쇠(별관 복도)"),
            new Poi("lock-work", 14, 15.6, true, false, "작업장 문 자물쇠(별관 복도)"),
            new Poi("lock-med", 30, 15.6, true, false, "의무실 문 자물쇠(별관 복도)"),
            new Poi("lock-laundry", 30, 18.4, true, false, "세탁실 문 자물쇠(별관 복도)"),
            new Poi("note-cafe-menu", 7.5, 19.6, false, false, "오늘의 식단표(별관 복도)"),
            new Poi("note-cafe-order", 10.5, 19.6, false, false, "배식 순서표(별관 복도)"),
            new Poi("note-pipe-map", 27, 19.6, false, false, "배관 노선도(별관 복도)"),
            new Poi("note-laundry-plan", 26.5, 20.4, false, false, "오늘 세탁 일정(별관 복도)"),
            // 방 **안**의 표식 퀴즈. 문이 닫혀 있는 동안은 Room.unreachableFor가 통째로 걸러 내므로
            // 봇이 잠긴 문 앞으로 걸어가는 일은 없다 — 사람이 문을 연 뒤에야 목표가 된다.
            // (식당 안 lock-fridge·note-cafe-tray는 아직 넣지 않았다. 조리실이 벽으로 나뉘어 있어
            //  들어갈 개구부를 확인한 뒤에 넣는 게 안전하다. 격자 길찾기로 바꾼 뒤로는 경로가
            //  있으면 Nav.reachabilityReport가 알려 주므로, 넣고 부팅 로그를 보면 된다.)
            new Poi("quiz-work", 18.5, 11.5, true, false, "작업대 표식(작업장 안)"),
            new Poi("quiz-med", 34.5, 11.2, true, false, "역학 조사서(의무실 안)"),
            new Poi("quiz-laundry", 26, 22.6, true, false, "건조대 표식(세탁실 안)")
            // 최종 탈출구(배수관)와 정문 자물쇠는 POI에 없다.
            //
            // 정문은 출구가 아니라 함정이라 뺐다(봇이 함정으로 걸어가면 안 된다).
            // 배수관(escape-pipe, 30/29)은 2026-07-27에 뺐다 — 봇이 한 판 내내 세탁실 문 앞에
            // 붙어 서 있던 원인이었다. botSolvable=false라 풀지도 못하는데 solvable=true여서
            // nearestUnsolved가 계속 골랐고, 그 좌표의 최근접 웨이포인트가 하필 **잠긴 세탁실
            // 안**(옛 웨이포인트 노드 10)이라 봇이 문 앞에 멈춘 채 판이 끝났다. 자기 감방을 푼 뒤엔
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
        List<Poi> near = nearestUnvisited(x, z, exclude, 1, false, Set.of());
        return near.isEmpty() ? null : near.get(0);
    }

    /**
     * 가까운 순 최대 n개 후보. 호출부(BotBrain)가 그중 하나를 무작위로 고른다.
     *
     * 늘 최근접만 고르면 순회 코스가 완전히 결정적이라, 봇이 <b>52초 주기로 똑같은 길</b>을
     * 돈다(2026-08-01 실측). 사람은 그렇게 안 움직인다 — 마지막이 AI 지목 투표라 이게 곧 단서다.
     *
     * @param solvableOnly true면 안 풀린 해결 대상만, false면 쪽지만 본다
     */
    static List<Poi> nearestUnvisited(double x, double z, Set<String> exclude, int n,
                                      boolean solvableOnly, Set<String> solved) {
        List<Poi> cand = new ArrayList<>();
        for (Poi p : ALL) {
            if (p.solvable() != solvableOnly || exclude.contains(p.id())) {
                continue;
            }
            if (solvableOnly && solved.contains(p.id())) {
                continue;
            }
            cand.add(p);
        }
        cand.sort((a, b) -> Double.compare(d2(a, x, z), d2(b, x, z)));
        return cand.size() <= n ? cand : cand.subList(0, n);
    }

    private static double d2(Poi p, double x, double z) {
        double dx = p.x() - x;
        double dz = p.z() - z;
        return dx * dx + dz * dz;
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
