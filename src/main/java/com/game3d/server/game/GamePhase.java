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
     * 예전엔 이 단계가 없어서 방이 만들어지는 순간 ONBOARDING이 시작됐다. 방은 첫 사람이
     * 대기방에 들어오면 생기므로, 아무도 시작을 누르지 않았는데 20분 시계가 흐르고 있었다.
     */
    LOBBY("대기 중"),

    /** 온보딩: 규칙 안내·조작 익히기. */
    ONBOARDING("온보딩"),

    /** 개별 미션 수행(순찰 1회 포함). */
    MISSION("개별 미션"),

    /** 정보 공유 + 조각 조합. */
    SHARING("정보 공유"),

    /** AI 투표 + 결말 연출. */
    VOTE("AI 투표"),

    /** 종료. 시간이 더 흘러도 여기서 멈춘다(길이 없음). */
    ENDED("종료");

    private final String label;

    GamePhase(String label) {
        this.label = label;
    }

    /** 프론트 표시용 한글 이름. */
    public String label() {
        return label;
    }

    /** 시간으로 진행하는 단계들(ENDED 제외). 순서 = 타임라인 순서. */
    public static final GamePhase[] TIMELINE = { ONBOARDING, MISSION, SHARING, VOTE };
}
