# AI 봇 작업 노트

방탈출 게임의 서버 사이드 AI 동료 봇. 담당: 이재성(플레이어·이동 + AI 봇).

## 지금 어디까지 됐나

| 단계 | 내용 | 상태 |
|------|------|------|
| 1단계 | 서버 사이드 봇 골격 + 스크립트 스티어링 | 완료 (`edde3e9`) |
| 2단계 | Groq LLM 느린 층 + Action 확장 | 완료 (이 문서) |
| 3단계 | 인게임 채팅 연결 후 `say`(대사) | 미착수 |

## 구조: 3겹

봇은 사람과 똑같이 "이동 의도(단위벡터)"만 만들고, 실제 이동·충돌·회전은 `Room.tick`이 사람과 공유
처리한다. **그래서 벽 회피는 공짜로 따라온다** — 봇 전용 경로탐색 코드가 없는 이유다.

```
빠른 층   BotBrain.steer()      매 tick(50ms). 목표로 향하는 단위벡터만. 절대 블로킹하지 않는다.
느린 층A  스크립트(reconsider)   항상 즉시 목표를 채우는 바닥. 안 풀린 퍼즐 중 최근접.
느린 층B  GroqBotPlanner        6초 주기, 가상 스레드. 성공하면 goal을 덮어쓴다.
```

핵심은 **B가 죽어도 게임이 안 멈춘다**는 것. `goal`이 volatile이고, LLM 실패(타임아웃·429·파싱실패·
없는 id 지어냄)는 전부 조용히 무시된다 → 봇은 스크립트 목표로 계속 움직인다. 우아한 열화가 설계의 축이다.

### 파일

| 파일 | 역할 |
|------|------|
| `BotBrain` | 3겹 조율. 도착 판정, LLM 호출 트리거, 결과 검증 |
| `BotPlanner` | 느린 층 인터페이스 (블로킹 허용) |
| `GroqBotPlanner` | Groq 호출 + 프롬프트 + 응답 파싱 |
| `BotContext` | 계획 시점 월드 사본(불변). 루프 스레드 → 플래너 스레드 |
| `BotProperties` | `game.bot.llm.*` 설정 |
| `Interactables` | 봇 목표 지점(POI) 레지스트리. 1단계 `Puzzles`를 대체 |
| `Goal` | `GOTO_PUZZLE` / `GOTO_NOTE` / `FOLLOW_PLAYER` / `IDLE` |

## 켜는 법

기본은 **꺼져 있다**(`game.bot.llm.enabled: false`). 켜두면 브라우저만 열어놔도 무료 한도가 샌다 —
`Room.join()`이 방마다 봇을 자동 스폰하기 때문.

```bash
# GROQ_API_KEY는 Windows 사용자 환경변수에 이미 설정돼 있음(gsk_...)
./gradlew bootRun --args="--game.bot.llm.enabled=true --logging.level.com.game3d.server.game=DEBUG"
```

DEBUG를 켜면 `봇 계획: GOTO_NOTE note-1 (문 코드 힌트 확인)` 처럼 모델의 판단 이유가 찍힌다.

> JDK 21 지정 빌드 필요: `-Dorg.gradle.java.home=$USERPROFILE/.jdks/ms-21.0.11`

## ⚠️ Groq 무료 티어 한도가 설계 제약이다

2026-07-15 실측(`x-ratelimit-*` 헤더). **모델 바꾸기 전에 반드시 다시 계산할 것.**

| 모델 | 요청/일 | 토큰/분 | 봇 1개 연속 가동 | 비고 |
|------|--------:|--------:|------------------|------|
| `openai/gpt-oss-20b` (**채택**) | 1,000 | 8,000 | **100분** | |
| `llama-3.1-8b-instant` | 14,400 | 6,000 | 약 20시간 | ❌ **2026-08-16 종료** |
| `llama-3.3-70b-versatile` | 1,000 | 12,000 | 100분 | ❌ **2026-08-16 종료** |

**모델 선택의 핵심**: 한도만 보면 `llama-3.1-8b-instant`가 14배 낫지만 **2026-08-16에 종료된다**
(무료·개발자 티어. 2026-06-17 공지, 대체안이 gpt-oss-20b). 그래서 한도를 포기하고 gpt-oss-20b를 택했다.
Groq 하면 떠오르는 게 llama라 무심코 되돌리기 쉬운데, 되돌리면 8/16에 봇이 죽는다.

6초 주기 = 10콜/분 → **1,000 요청/일 ÷ 10 = 하루 100분**이 전부다. 15분 플레이 기준 6~7판.
기본이 off인 이유가 이것이다. 토큰은 약 500/콜 × 10 ≈ 5,000 TPM으로 8,000 안쪽이라 여유 있다.
**주기를 줄이거나 방을 늘리면 요청 수가 먼저 터진다.**

## ⚠️ Spring Boot 4 = Jackson 3

Boot 4.0.7은 **Jackson 3.1.4(`tools.jackson.*`)** 를 쓴다. 2.x와 다르다:

- `com.fasterxml.jackson.databind`는 **클래스패스에 아예 없다** (annotations만 `com.fasterxml`에 남음)
- `JacksonException`은 **unchecked**다 (2.x `JsonProcessingException`과 다름) → `throws` 불필요
- `asText()`는 deprecated, **`asString()`** 으로 개명됨

HTTP도 Spring `RestClient` 대신 JDK `HttpClient`를 썼다. Boot 4의 관례 변화 리스크를 피하고
새 의존성도 필요 없다.

파싱 함정 하나: 모델이 `"targetId": null`을 주면 문자열 `"null"`로 읽힌다. `isNull()`로 명시적으로
걸러야 `IDLE`이 성립한다(`GroqBotPlanner.parseGoal`).

## 검증

`D:\tryThat\botcheck3.mjs` (레포 밖, botcheck2와 같은 위치). 30초간 STOMP 스냅샷을 받아 판정한다.

```bash
node botcheck3.mjs 8081     # 포트 인자, 기본 8080
```

**판별 원리**: 스크립트 층은 `solvable=true`인 POI만 고르므로 `note-1`(쪽지)에 **절대 가지 않는다**.
따라서 봇이 note-1에 도달했다면 그 목표는 LLM이 낸 것(`GOTO_NOTE`)이다.

2026-07-15 결과 — note-1 최소 접근 1.23m ✅ → LLM 느린 층 동작 확인.
⚠️ 단 이건 **`llama-3.1-8b-instant`로 돌린 결과**다. 채택 모델(gpt-oss-20b)로는 아직 안 돌려봤다(위 이슈 0번).

## 알려진 이슈 / 다음에 볼 것

0. 🚨 **gpt-oss-20b는 아직 게임에서 실증되지 않았다.** 봇 동작 검증(botcheck3)은 `llama-3.1-8b-instant`로
   통과한 결과다. 8/16 종료 때문에 막판에 모델만 gpt-oss-20b로 바꿨고, **바꾼 뒤 서버로 돌려보지 못했다.**
   다음 세션에서 `node botcheck3.mjs 8081`을 먼저 다시 돌릴 것.

   바꾸면서 확인한 것 — **gpt-oss는 추론 모델이라 답 앞에 추론 토큰을 쓴다.** 단독 호출 실측(4회)에서
   completion이 110~118토큰까지 올라갔고, `max_tokens: 120`이던 시절 **4회 중 1회가 HTTP 400
   `json_validate_failed`(failed_generation 비어 있음)** 로 실패했다. 추론하다 토큰이 떨어져 JSON을
   못 끝낸 것으로 보인다. → `max_tokens`를 400으로 올려뒀다. **이 수정은 미검증이다.**

   여전히 400이 나오면 다음 후보: **`reasoning_effort: "low"`** 를 요청 바디에 추가(gpt-oss 전용 파라미터).
   추론 토큰 자체를 줄이는 정공법이고, 레이턴시도 같이 준다. 시간이 없어 실험을 못 했다.
   실패해도 게임은 안 멈춘다(스크립트 폴백) — 다만 그만큼 봇이 멍청해진다.
   서버 로그의 `봇 계획 실패(IOException)` 빈도로 확인할 것.

1. **봇이 note → door → lockbox를 무한 반복한다.** 검증 중 관측됨. 아무도 퍼즐을 안 푸는 상황이라
   6초마다 LLM이 "힌트부터 확인해"(`GOTO_NOTE`)를 다시 내고, 도착하면 스크립트가 퍼즐로 보내는 게
   반복되는 것으로 보인다. **봇에겐 "쪽지를 이미 읽었다"는 상태가 없다.** `lastArrivedId`를 프롬프트에
   넣어두긴 했지만 8b가 무시하는 듯. 사람이 퍼즐을 푸는 실제 플레이에선 상태가 바뀌니 덜 두드러지겠지만,
   **읽은 쪽지 기록을 봇 상태로 남기는 게 정공법**이다. 서버 로그의 `봇 계획:` 라인을 먼저 확인할 것.
2. **`FOLLOW_PLAYER`는 실증되지 않았다.** 검증에서 사람 최근접 0.64m가 나왔지만, 봇 스폰(0,0)과
   사람 스폰(1,0)이 붙어 있어서 생긴 우연일 수 있다. 사람을 멀리 옮겨놓고 다시 봐야 한다.
3. **`say`(대사)** — `Goal`에 자리만 잡아둔 상태. 인게임 채팅(조예원 담당)이 붙은 뒤에 추가한다.
   방탈출 봇의 존재감은 사실상 여기서 나온다.
4. **프론트 동기화** — `Interactables.java`의 id·좌표는 프론트 `game/interactables.ts`와 반드시
   일치해야 한다. 한쪽 바꾸면 양쪽 반영.
