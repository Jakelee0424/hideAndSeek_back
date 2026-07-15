package com.game3d.server.game;

/**
 * 느린 층: 봇이 다음에 무엇을 할지 정한다.
 *
 * 구현은 블로킹이어도 된다. 호출자(BotBrain)가 가상 스레드에서 돌리고, 결과가 늦거나 없으면
 * 마지막 goal을 그대로 유지한다(우아한 열화).
 */
interface BotPlanner {

    /**
     * @return 새 목표. 정할 수 없으면 null(호출자가 기존 목표를 유지한다).
     * @throws Exception 호출 실패는 던져도 된다. 호출자가 삼키고 로깅한다.
     */
    Goal plan(BotContext ctx) throws Exception;
}
