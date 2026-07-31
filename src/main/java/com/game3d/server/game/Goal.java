package com.game3d.server.game;

/**
 * 봇이 "무엇을 할지". 느린 층(BotPlanner)이 정하고 빠른 층(tick)이 실행한다.
 *
 * emote는 이 계획과 함께 드러낼 감정표현 하나다(없으면 null). 예전엔 자유 문장(say)이었는데,
 * 프론트에서 텍스트 채팅을 없애고 1~4 단축키 감정표현으로 바꾸면서 봇도 같은 표현만 낸다.
 * 값은 hello|laugh|sad|angry 중 하나(프론트 net/emotes.ts의 EmoteId와 이중 관리). 이동 목표와
 * 한 번에 받는 이유는 LLM 호출을 두 번 하지 않기 위해서다 — 무료 한도가 빠듯해 호출 수가 곧 비용이다.
 *
 * ⚠️ emote는 봇이 <b>사람인 척</b> 하는 유일한 수단이다. 마지막 단계가 AI 지목 투표라, 아무
 *    표현도 안 하면 "한 번도 표현 안 한 놈 = AI"가 되어 게임이 성립하지 않는다
 *    (표현 규칙은 GroqBotPlanner.SYSTEM 참고).
 */
record Goal(Action action, String targetId, String emote) {

    /** 감정표현 없는 목표. 스크립트(빠른 층)가 만드는 목표는 전부 이쪽이다 — 표현은 LLM만 낸다. */
    Goal(Action action, String targetId) {
        this(action, targetId, null);
    }

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

    static final Goal IDLE = new Goal(Action.IDLE, null, null);

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
