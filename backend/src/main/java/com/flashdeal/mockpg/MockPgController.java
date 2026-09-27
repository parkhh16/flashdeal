package com.flashdeal.mockpg;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 외부 PG사를 흉내 낸 가짜 서버. 같은 JVM에 있지만 반드시 HTTP로만 호출해서
 * 실제 외부 연동처럼 타임아웃, 5xx, 네트워크 지연이 발생하게 만든다.
 * 실제 PG처럼 paymentKey 기준으로 멱등하게 동작한다 (같은 키로 다시 호출하면 기존 결과를 반환).
 */
@Hidden
@Slf4j
@RestController
@RequestMapping("/mock-pg/v1/payments")
@RequiredArgsConstructor
public class MockPgController {

    public record ApproveRequest(String paymentKey, String orderNo, long amount) {
    }

    public record PgPayment(String paymentKey, String status, String transactionId, long amount, String reason) {
    }

    private final ChaosSettings chaos;
    private final Map<String, PgPayment> store = new ConcurrentHashMap<>();

    @PostMapping
    public ResponseEntity<PgPayment> approve(@RequestBody ApproveRequest request) throws InterruptedException {
        ChaosSettings.Snapshot c = chaos.get();
        Thread.sleep(c.latencyMs());

        PgPayment existing = store.get(request.paymentKey());
        if (existing != null) {
            return respond(existing);
        }
        if (roll(c.failureRate())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (roll(c.declineRate())) {
            return respond(save(new PgPayment(request.paymentKey(), "DECLINED", null, request.amount(), "LIMIT_EXCEEDED")));
        }
        PgPayment approved = save(new PgPayment(request.paymentKey(), "APPROVED",
                "TX-" + UUID.randomUUID().toString().substring(0, 12), request.amount(), null));
        if (roll(c.timeoutRate())) {
            Thread.sleep(3_000); // 승인은 끝났지만 응답이 클라이언트 read-timeout(2s)보다 늦게 도착한다
        }
        return respond(approved);
    }

    @GetMapping("/{paymentKey}")
    public ResponseEntity<PgPayment> inquire(@PathVariable String paymentKey) {
        PgPayment payment = store.get(paymentKey);
        return payment == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(payment);
    }

    /** 승인 취소(환불). 이미 취소된 건이면 같은 결과를 돌려준다 (멱등) */
    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<PgPayment> cancel(@PathVariable String paymentKey) {
        PgPayment result = store.computeIfPresent(paymentKey, (key, p) -> switch (p.status()) {
            case "APPROVED" -> new PgPayment(key, "CANCELLED", p.transactionId(), p.amount(), "CANCELLED_BY_MERCHANT");
            default -> p;
        });
        if (result == null) return ResponseEntity.notFound().build();
        if (!"CANCELLED".equals(result.status())) return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        return ResponseEntity.ok(result);
    }

    private PgPayment save(PgPayment payment) {
        PgPayment prev = store.putIfAbsent(payment.paymentKey(), payment);
        return prev != null ? prev : payment;
    }

    private ResponseEntity<PgPayment> respond(PgPayment payment) {
        return "APPROVED".equals(payment.status())
                ? ResponseEntity.ok(payment)
                : ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(payment);
    }

    private boolean roll(double rate) {
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }
}
