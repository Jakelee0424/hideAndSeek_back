# CLAUDE.md — game3d-server (백엔드)

실시간 멀티플레이 3D 게임의 **권위(authoritative) 서버**. Spring Boot로 REST API + **WebSocket/STOMP** 실시간 통신을 제공하고, 게임 상태(플레이어 위치·룸)를 서버가 확정해 클라이언트에 브로드캐스트한다.

> 짝 프로젝트(프론트): `C:\Users\jung\project\game3d-client` — Next.js + React Three Fiber. 클라이언트는 입력을 보내고 서버 스냅샷을 렌더링만 한다.

---

## 기술 스택 / 제약
- **Java 21** (LTS) — records, sealed, pattern matching, **Virtual Threads** 적극 활용 가능
- **Spring Boot 4.x** (Spring Framework 7 기반) — Jakarta EE 10+, `jakarta.*` 네임스페이스
- 빌드: **Gradle (Kotlin DSL)** 권장
- **DB: 인메모리** — **H2 (in-memory)** + Spring Data JPA. 서버 재시작 시 데이터 초기화됨(의도된 것).
- 실시간: **Spring WebSocket + STOMP** (`spring-boot-starter-websocket`)

> ⚠️ Spring Boot 4.x는 신버전이라 스타터/설정 관례가 3.x와 다를 수 있음. 버전 관련 API/프로퍼티는 **추측하지 말고** 공식 문서/실제 의존성 확인 후 사용.

## 로컬 실행
```bash
./gradlew bootRun          # http://localhost:8080
```
- H2 콘솔: `http://localhost:8080/h2-console` (`application.yml`에서 `spring.h2.console.enabled=true`)
- 포트/CORS: 프론트가 `localhost:3000` 이므로 **CORS 및 WS Origin 허용** 설정 필요.

---

## 아키텍처: 권위 서버 모델
- **서버가 진실의 원천.** 클라이언트 입력 = "이동 의도(방향 벡터)" 로 받고, 서버가 검증·적용해 다음 상태를 만든다. 클라이언트가 보낸 좌표를 그대로 신뢰하지 말 것(치팅 방지).
- **게임 루프(tick).** 고정 주기(예: 20 tick/s = 50ms)로 룸별 상태를 갱신하고 스냅샷을 브로드캐스트. 스케줄러(`@Scheduled` 고정 지연) 또는 룸별 전용 루프로 구현.
- **상태 보관은 메모리 우선.** 실시간 플레이어 위치/속도 같은 hot state는 DB가 아니라 **메모리(룸 객체 안 ConcurrentHashMap 등)** 에 둔다. H2(JPA)에는 상대적으로 영속성 의미가 있는 것(계정·전적 등, 필요 시)만.
- 동시성: 룸 단위로 상태를 격리하고, 공유 자료구조는 thread-safe 컬렉션 사용. 필요 시 룸당 단일 스레드로 직렬화.

## STOMP 목적지 규약 (프론트와 반드시 일치)
STOMP 엔드포인트: `/ws` — 프론트 `NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws`

| 방향 | 목적지 | 용도 |
|------|--------|------|
| 클라 → 서버 | `/app/rooms/{roomId}/input` | 입력/이동 의도 전송 (`@MessageMapping`) |
| 클라 → 서버 | `/app/rooms/{roomId}/join` | 룸 입장 |
| 서버 → 클라 | `/topic/rooms/{roomId}/state` | 월드 스냅샷 브로드캐스트 (`SimpMessagingTemplate`) |

- 브로커: 초기엔 내장 SimpleBroker(`/topic`)로 충분. 스케일아웃 시 외부 브로커 검토.
- 접속/이탈은 `SessionConnected`/`SessionDisconnect` 이벤트 리스너로 룸 인원 관리.

## REST API (보조)
- 로비/룸 목록·생성, 헬스체크 등 실시간이 아닌 것만 REST로. 실시간 상태는 STOMP.
- DTO는 **record** 로. 프론트 TypeScript 인터페이스와 필드명·좌표 규약(y-up, 미터 등) 일치.

## 디렉터리 컨벤션(권장)
```
config/     # WebSocketConfig(STOMP), CORS, H2
game/       # 게임 루프, 룸(Room), 플레이어 상태, tick 로직
  Room.java
  GameLoop.java
net/        # @MessageMapping 컨트롤러, 브로드캐스터
web/        # REST 컨트롤러(로비 등)
domain/     # JPA 엔티티(영속 대상만) + repository
dto/        # record 기반 요청/응답·메시지
```

## 코드 스타일
- Java 21 관용구 사용(record/sealed/switch 패턴), 불변 선호.
- 실시간 경로(핫패스)는 **할당·로깅 최소화**. 프레임/틱마다 객체 새로 만들지 않기.
- 좌표·단위·메시지 스키마는 **프론트 CLAUDE.md와 동기화**. 한쪽 바꾸면 양쪽 반영.
- 커밋/푸시는 **사용자가 요청할 때만**. **커밋 메시지는 한글로 작성**한다.
