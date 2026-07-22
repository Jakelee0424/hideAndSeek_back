package com.game3d.server.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 탈옥 시나리오 플랜 — 방 코드를 시드로 매판 랜덤 생성한다.
 *
 * 정보를 인원 수만큼 쪼개는 재설계(2026-07)의 서버 몫: 감방(A~D)마다 "표식 + 수"가 배정되고,
 * 탈옥문 4자리 코드는 감방당 한 자리씩이다. 서버는 <b>봇이 채팅으로 말할 자기 감방 단서</b>를
 * 얻는 데만 이 플랜을 쓴다 — 코드 검증은 기존 신뢰 모델대로 클라 퍼즐 UI가 한다.
 *
 * ⚠️ 프론트 game/escapePlan.ts 와 시드 해시(FNV-1a)·난수(LCG)·소비 순서가 <b>완전히 같아야</b>
 *    한다. 32비트 정수 연산이라 언어가 달라도 비트가 같다 — 한쪽 고치면 양쪽 반영.
 */
final class EscapePlan {

    /** 표식 풀(8개 중 매판 4개). 프론트 escapePlan.SYMBOLS와 같은 값·순서. */
    private static final String[] SYMBOLS = {"닻", "별", "달", "해", "새", "물고기", "열쇠", "왕관"};

    /** 감방 하나의 단서: 표식, 표식의 수(0~9), 코드에서의 자리(0=첫째). */
    record Clue(String symbol, int value, int position) {}

    private static final Map<String, EscapePlan> CACHE = new ConcurrentHashMap<>();

    private final Clue[] clues = new Clue[4]; // 감방 index 순(0=1-1 … 3=1-4). Room.CELL_CENTERS와 같은 순서.
    private final String code;
    private final int shift;

    private EscapePlan(String seed) {
        // ⚠️ rand 소비 순서가 프론트와의 계약이다: 표식 셔플 → 자리 셔플 → 수 4개 → 보정.
        int h = hash("escape|" + seed);
        int[] s = {h == 0 ? 1 : h};

        String[] syms = SYMBOLS.clone();
        for (int i = syms.length - 1; i > 0; i--) {
            int j = (int) Math.floor(rand(s) * (i + 1));
            String t = syms[i];
            syms[i] = syms[j];
            syms[j] = t;
        }
        int[] pos = {0, 1, 2, 3};
        for (int i = pos.length - 1; i > 0; i--) {
            int j = (int) Math.floor(rand(s) * (i + 1));
            int t = pos[i];
            pos[i] = pos[j];
            pos[j] = t;
        }
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            values[i] = (int) Math.floor(rand(s) * 10);
        }
        this.shift = 1 + (int) Math.floor(rand(s) * 9);

        char[] digits = new char[4];
        for (int i = 0; i < 4; i++) {
            clues[i] = new Clue(syms[i], values[i], pos[i]);
            digits[pos[i]] = (char) ('0' + (values[i] + shift) % 10);
        }
        this.code = new String(digits);
    }

    /** 방 코드로 플랜을 얻는다(캐시). 같은 방이면 프론트와 같은 코드·표식이 나온다. */
    static EscapePlan of(String seed) {
        return CACHE.computeIfAbsent(seed == null || seed.isEmpty() ? "solo" : seed, EscapePlan::new);
    }

    /** 감방 index(0~3)의 단서. */
    Clue clue(int cellIdx) {
        return clues[cellIdx];
    }

    /** 탈옥문 4자리 코드(참고용 — 검증은 클라가 한다). */
    String code() {
        return code;
    }

    int shift() {
        return shift;
    }

    /** 문자열 → 32bit 정수(FNV-1a). 프론트 escapePlan.hash와 같은 식. */
    private static int hash(String str) {
        int h = 0x811c9dc5;
        for (int i = 0; i < str.length(); i++) {
            h ^= str.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    /** LCG 한 걸음(상태 배열 제자리 갱신, 0~1 반환). 프론트 escapePlan.rng와 같은 식. */
    private static double rand(int[] s) {
        s[0] = s[0] * 1664525 + 1013904223;
        return (s[0] & 0xFFFFFFFFL) / 4294967296.0;
    }
}
