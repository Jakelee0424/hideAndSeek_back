package com.game3d.server.game;

/**
 * 게임 진행 단계. 시간이 흐르면 선언 순서대로 진행하고, 마지막 ENDED에서 멈춘다.
 *
 * 각 단계의 길이는 여기 박지 않고 {@link PhaseProperties}(game.phases.*)에서 읽는다.
 * 데모 때 20분을 다 기다릴 수 없으니 설정으로 줄일 수 있어야 하기 때문이다.
 */
public enum GamePhase {

    /**
     * 대기방. 전원이 준비를 마치고 방장이 시작을 누를 때까지 머문다. 길이가 없다(시계가 안 흐른다).
     *
     * 예전엔 이 단계가 없어서 방이 만들어지는 순간 게임 시계가 시작됐다. 방은 첫 사람이
     * 대기방에 들어오면 생기므로, 아무도 시작을 누르지 않았는데 20분 시계가 흐르고 있었다.
     */
    LOBBY("대기 중"),

    /**
     * 플레이 전체: 도입 내레이션 → 감방 탈출 → 단서 수집·공유 → 배수관 탈출. 순찰이 도는 구간.
     *
     * 2026-07-31에 옛 ONBOARDING·MISSION·SHARING 셋을 여기로 합쳤다(사용자 지시). 셋은 시계로만
     * 갈렸을 뿐 규칙이 달라지지 않아, 화면엔 단계 이름만 바뀌고 할 일은 그대로인 구간이었다.
     * 게다가 감방을 못 나온 채 "단서 공유"로 넘어가는 등 이름과 실제가 자주 어긋났다.
     * 도입 내레이션 길이는 단계가 아니라 {@link PhaseProperties#intro()}로 남아 있다 —
     * 순찰이 내레이션 위로 겹쳐 도는 걸 막는 데 그 값이 필요하기 때문이다.
     */
    PLAY("탈옥"),

    /** AI 투표 + 결말 연출. */
    VOTE("색출"),

    /** 종료. 시간이 더 흘러도 여기서 멈춘다(길이 없음). */
    ENDED("자정");

    private final String label;

    GamePhase(String label) {
        this.label = label;
    }

    /** 프론트 표시용 한글 이름. */
    public String label() {
        return label;
    }

    /** 시간으로 진행하는 단계들(ENDED 제외). 순서 = 타임라인 순서. */
    public static final GamePhase[] TIMELINE = { PLAY, VOTE };
}
