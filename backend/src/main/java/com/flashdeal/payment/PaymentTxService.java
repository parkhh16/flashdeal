package com.flashdeal.payment;

import com.flashdeal.order.Order;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderService;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.payment.PgClient.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 결제 흐름에서 DB 트랜잭션 구간만 모아 둔 서비스.
 * PG 호출(최대 수 초)을 트랜잭션 밖으로 빼서, 외부 지연이 DB 커넥션 풀 고갈로 번지지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTxService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    public record Prepared(Long paymentId, Long orderId, String orderNo, String paymentKey, long amount) {
    }

    /** [TX 1] 주문을 PAYING으로 바꾸고, PG 호출 "전에" 결제 시도를 기록한다 */
    @Transactional
    public Prepared prepare(Long userId, Long orderId) {
        Order order = orderService.findMyOrder(userId, orderId);
        order.startPayment(LocalDateTime.now(clock));

        long attempt = paymentRepository.countByOrderId(orderId) + 1;
        String paymentKey = order.getOrderNo() + "-P" + attempt;
        Payment payment = paymentRepository.save(new Payment(orderId, paymentKey, order.getTotalAmount()));

        log.info("event=PAYMENT_REQUESTED orderNo={} paymentKey={} amount={}",
                order.getOrderNo(), paymentKey, order.getTotalAmount());
        return new Prepared(payment.getId(), orderId, order.getOrderNo(), paymentKey, order.getTotalAmount());
    }

    /** [TX 2] PG 결과를 결제와 주문에 함께 반영한다. 사용자 요청 흐름과 대사 스케줄러가 같이 사용한다 */
    @Transactional
    public PaymentStatus complete(Long paymentId, PgResult result) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        if (payment.isFinished()) {
            return payment.getStatus(); // 이미 다른 경로(요청 흐름과 대사)에서 확정했다
        }
        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();

        switch (result.outcome()) {
            case APPROVED -> {
                payment.approve(result.transactionId());
                order.markPaid();
                log.info("event=PAYMENT_APPROVED orderNo={} paymentKey={} pgTx={}",
                        order.getOrderNo(), payment.getPaymentKey(), result.transactionId());
            }
            case DECLINED, NOT_FOUND, CANCELLED -> {
                payment.fail(result.reason());
                order.paymentFailed(LocalDateTime.now(clock));
                if (order.getStatus() == OrderStatus.EXPIRED) {
                    orderService.restoreStock(order);
                }
                log.info("event=PAYMENT_FAILED orderNo={} paymentKey={} reason={} orderStatus={}",
                        order.getOrderNo(), payment.getPaymentKey(), result.reason(), order.getStatus());
            }
            case UNKNOWN -> {
                payment.markUnknown(result.reason());
                log.warn("event=PAYMENT_UNKNOWN orderNo={} paymentKey={} reason={}",
                        order.getOrderNo(), payment.getPaymentKey(), result.reason());
            }
        }
        return payment.getStatus();
    }
}
