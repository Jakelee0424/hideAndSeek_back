package com.game3d.server.game;

/**
 * 봇이 "무엇을 할지". 느린 층(BotPlanner)이 정하고 빠른 층(tick)이 실행한다.
 *
 * 2단계에서 LLM이 말할 내용(say)도 함께 낼 예정이나, 인게임 채팅(조예원 담당)과
 * 연결된 뒤에 추가한다. 지금 넣어봐야 소비하는 쪽이 없다.
 */
record Goal(Action action, String targetId) {

    enum Action {
        /** targetId 퍼즐로 이동(solvable=true인 POI). */
        GOTO_PUZZLE,
        /** targetId 쪽지로 이동해 힌트 확인(solvable=false인 POI). */
        GOTO_NOTE,
        /** targetId 사람 플레이어를 따라간다. 도착해도 목표를 유지하고 곁에 머문다. */
        FOLLOW_PLAYER,
        /** 할 일 없음(정지). */
        IDLE
    }

    static final Goal IDLE = new Goal(Action.IDLE, null);

    static Goal gotoPuzzle(String puzzleId) {
        return new Goal(Action.GOTO_PUZZLE, puzzleId);
    }

    static Goal gotoNote(String noteId) {
        return new Goal(Action.GOTO_NOTE, noteId);
    }

    static Goal followPlayer(String playerId) {
        return new Goal(Action.FOLLOW_PLAYER, playerId);
    }
}
