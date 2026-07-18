package com.game3d.server.net;

import com.game3d.server.dto.QueueTicket;
import com.game3d.server.game.WaitingQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 접속 대기열 REST. 게임 트래픽(STOMP)과 분리해 REST로 둔 이유는 단순하다 —
 * 대기열은 "아직 게임에 못 들어온 사람"이 쓰는 것이라, 게임 연결 위에 얹으면 앞뒤가 바뀐다.
 *
 * 순번 갱신은 1초 폴링이다. SSE/WS로 밀어줄 수도 있지만 대기 인원이 수십 명 규모라
 * 폴링이 훨씬 단순하고 실패 처리(새로고침·네트워크 끊김)가 저절로 된다.
 */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    /** 대기열 진입 요청. 프론트 net/queue.ts와 필드명 일치. */
    public record EnterRequest(String id, String nick) {}

    private final WaitingQueue queue;

    public QueueController(WaitingQueue queue) {
        this.queue = queue;
    }

    /** 진입(멱등). 이미 슬롯이 있으면 그 토큰을, 줄 서 있으면 순번을 그대로 돌려준다. */
    @PostMapping
    public ResponseEntity<QueueTicket> enter(@RequestBody EnterRequest req) {
        if (req == null || req.id() == null || req.id().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(queue.enter(req.id(), req.nick()));
    }

    /** 폴링. 대기열에서 사라졌으면(만료 등) 404 → 클라는 다시 enter를 부른다. */
    @GetMapping("/{playerId}")
    public ResponseEntity<QueueTicket> status(@PathVariable String playerId) {
        QueueTicket ticket = queue.status(playerId);
        return ticket == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(ticket);
    }

    /** 대기 취소 / 자리 반납. 브라우저를 닫는 경우는 WS 종료 이벤트가 대신 처리한다. */
    @DeleteMapping("/{playerId}")
    public ResponseEntity<Void> leave(@PathVariable String playerId) {
        queue.release(playerId);
        return ResponseEntity.noContent().build();
    }
}
