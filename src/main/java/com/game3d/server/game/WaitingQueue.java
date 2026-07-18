package com.game3d.server.game;

import com.game3d.server.dto.QueueTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 접속 대기열(Virtual Waiting Room). 정원을 넘는 접속을 FIFO로 세워두고 자리가 나면 승급시킨다.
 *
 * <h2>왜 Redis도 Spring Cache도 아닌가</h2>
 * 단일 인스턴스라 Redis는 과하다(배포 대상이 t2.micro 1GB인데 봇 LLM까지 돈다).
 * Spring Cache도 맞지 않는다 — 캐시는 키-값 맵이라 (1) FIFO 순서가 없고, (2) "정원이 남았으면
 * 배정, 아니면 대기"라는 check-then-act을 원자적으로 못 묶고, (3) TTL 만료가 조용해서
 * "만료됐으니 다음 사람을 올린다"는 후속 동작을 걸 수 없다.
 * 그래서 그냥 자료구조 둘 + 락 하나다. 다중 인스턴스로 가면 Redis ZSET으로 갈아끼우면 된다.
 *
 * <h2>동시성</h2>
 * REST 스레드 · WS 종료 이벤트 · 스케줄러가 함께 만진다. 모든 변경을 하나의 모니터로 감싼다.
 * 초당 몇 건 수준이라 락 경합은 문제가 안 되고, 원자성이 눈에 보이는 편이 훨씬 안전하다.
 *
 * <h2>슬롯의 수명</h2>
 * 승급(ADMITTED) 시점에 슬롯을 잡고, 실제 STOMP join이 올 때 connected로 표시한다.
 * 승급만 받고 안 들어오는 사람이 자리를 영구 점유하지 못하도록 tokenTtl 안에 접속하지 않으면 회수한다.
 * 접속에 성공한 뒤에는 TTL을 적용하지 않는다 — 게임 중에 쫓겨나면 안 되니까.
 */
@Component
public class WaitingQueue {

    private static final Logger log = LoggerFactory.getLogger(WaitingQueue.class);

    /** 입장 슬롯. connected=false면 "승급됐지만 아직 접속 전"이라 TTL 회수 대상이다. */
    private record Slot(String token, long admittedAtMs, boolean connected) {
        Slot connect() {
            return new Slot(token, admittedAtMs, true);
        }

        /** TTL 시계를 지금으로 되감는다(폴링 keepalive). */
        Slot touch() {
            return new Slot(token, System.currentTimeMillis(), false);
        }
    }

    private final QueueProperties props;

    private final Object lock = new Object();
    /** playerId → 슬롯. 정원 계산의 기준이다. */
    private final Map<String, Slot> slots = new HashMap<>();
    /** 대기 줄(FIFO). playerId만 담는다. */
    private final Deque<String> waiting = new ArrayDeque<>();

    public WaitingQueue(QueueProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * 대기열 진입(또는 현재 상태 조회). 같은 id로 다시 부르면 상태를 그대로 돌려준다
     * — 새로고침해도 순번이 뒤로 밀리지 않아야 한다(멱등).
     */
    public QueueTicket enter(String playerId, String nick) {
        synchronized (lock) {
            Slot mine = slots.get(playerId);
            if (mine != null) {
                return admittedTicket(mine);
            }
            if (waiting.contains(playerId)) {
                return waitingTicket(playerId);
            }
            if (slots.size() < props.capacity() && waiting.isEmpty()) {
                // 자리가 있고 기다리는 사람도 없다 → 즉시 입장. 줄이 있으면 새치기가 되므로 막는다.
                return admittedTicket(admit(playerId));
            }
            waiting.addLast(playerId);
            log.info("대기열 등록: {}({}) — 순번 {}", playerId, nick, waiting.size());
            return waitingTicket(playerId);
        }
    }

    /**
     * 폴링용 현재 상태. 대기열에도 슬롯에도 없으면 null(클라는 다시 enter를 부른다).
     *
     * 아직 접속 전인 슬롯은 이 호출이 keepalive 역할을 한다 — 폴링하는 동안 TTL이 갱신된다.
     * 접속 전에 로비에서 닉네임을 치는 시간이 tokenTtl보다 길어도 자리를 잃지 않아야 하기 때문이다.
     * 탭을 닫으면 폴링이 끊겨 그때부터 TTL이 흘러 정상 회수된다.
     */
    public QueueTicket status(String playerId) {
        synchronized (lock) {
            Slot mine = slots.get(playerId);
            if (mine != null) {
                if (!mine.connected()) {
                    mine = mine.touch();
                    slots.put(playerId, mine);
                }
                return admittedTicket(mine);
            }
            return waiting.contains(playerId) ? waitingTicket(playerId) : null;
        }
    }

    /**
     * STOMP join에서의 검증. 유효한 토큰이면 connected로 표시하고 true.
     *
     * 대기열이 꺼져 있거나, 줄이 비어 있고 정원도 남았다면 토큰 없이 온 접속도 받아준다
     * (슬롯은 잡는다). 팀원의 옛 클라가 조용할 때는 그대로 돌아가고, 붐빌 때만 게이트가 문다.
     */
    public boolean admitOnJoin(String playerId, String token) {
        if (!props.enabled()) {
            return true;
        }
        synchronized (lock) {
            Slot mine = slots.get(playerId);
            if (mine != null) {
                if (mine.token().equals(token)) {
                    slots.put(playerId, mine.connect());
                    return true;
                }
                // 토큰이 틀렸는데 슬롯은 있다 = 재접속(새 탭 등). 자리는 이미 그의 것이니 통과시킨다.
                slots.put(playerId, mine.connect());
                return true;
            }
            if (waiting.contains(playerId)) {
                return false; // 줄 서는 중인데 직접 접속을 시도했다 → 거부
            }
            if (slots.size() < props.capacity() && waiting.isEmpty()) {
                slots.put(playerId, admit(playerId).connect());
                return true;
            }
            log.info("입장 거부(정원 초과): {}", playerId);
            return false;
        }
    }

    /** 이탈(WS 종료 · 명시적 취소). 슬롯이든 대기든 지우고 다음 사람을 올린다. */
    public void release(String playerId) {
        synchronized (lock) {
            boolean had = slots.remove(playerId) != null;
            waiting.remove(playerId);
            if (had) {
                promote();
            }
        }
    }

    /** 승급만 받고 tokenTtl 안에 접속하지 않은 슬롯을 회수한다. 안 하면 자리가 영구히 샌다. */
    @Scheduled(fixedDelay = 1000)
    public void reclaimExpired() {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() - props.tokenTtl().toMillis();
            boolean freed = false;
            for (Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Slot> e = it.next();
                if (!e.getValue().connected() && e.getValue().admittedAtMs() < deadline) {
                    log.info("입장 유예 만료로 슬롯 회수: {}", e.getKey());
                    it.remove();
                    freed = true;
                }
            }
            if (freed) {
                promote();
            }
        }
    }

    // ── 내부(모두 lock 안에서만 호출) ────────────────────────────────────────

    /** 빈 자리만큼 줄 앞에서 끌어올린다. */
    private void promote() {
        while (slots.size() < props.capacity() && !waiting.isEmpty()) {
            String next = waiting.pollFirst();
            admit(next);
            log.info("대기열 승급: {} (남은 대기 {})", next, waiting.size());
        }
    }

    private Slot admit(String playerId) {
        Slot slot = new Slot(UUID.randomUUID().toString(), System.currentTimeMillis(), false);
        slots.put(playerId, slot);
        return slot;
    }

    private QueueTicket admittedTicket(Slot slot) {
        return QueueTicket.admitted(slot.token(), waiting.size(), props.capacity(), slots.size());
    }

    private QueueTicket waitingTicket(String playerId) {
        int position = 0;
        int i = 1;
        for (String id : waiting) {
            if (id.equals(playerId)) {
                position = i;
                break;
            }
            i++;
        }
        return QueueTicket.waiting(position, waiting.size(), props.capacity(), slots.size());
    }
}
