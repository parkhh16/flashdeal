package com.flashdeal.order;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.payment.Payment;
import com.flashdeal.payment.PaymentRepository;
import com.flashdeal.payment.PaymentStatus;
import com.flashdeal.payment.PgClient;
import com.flashdeal.payment.PgClient.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 주문 취소.
 * - 결제 대기: 주문 취소 + 재고 복구 (트랜잭션 하나)
 * - 결제 완료: PG 환불(트랜잭션 밖) → 성공한 경우에만 [결제 취소 + 주문 취소 + 재고 복구] (트랜잭션 하나)
 *   환불이 실패하면 아무것도 바꾸지 않는다. "우리 DB는 취소, PG는 결제 유지" 불일치를 만들지 않기 위해서다.
 * - 결제 중(PAYING): 결과를 모르는 상태라 취소하지 않는다 (대사 후 다시 시도)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final PgClient pgClient;
    private final PlatformTransactionManager transactionManager;

    public OrderStatus cancel(Long orderId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Order snapshot = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        return switch (snapshot.getStatus()) {
            case PENDING_PAYMENT -> tx.execute(s -> {
                Order order = orderRepository.findById(orderId).orElseThrow();
                order.cancel(); // 그 사이 결제가 시작됐다면 상태 전이 검증 또는 @Version 충돌로 실패한다
                orderService.restoreStock(order);
                log.info("event=ORDER_CANCELLED orderNo={} from=PENDING_PAYMENT", order.getOrderNo());
                return order.getStatus();
            });
            case PAID -> cancelPaid(orderId, tx);
            case PAYING -> throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS,
                    "결제 결과를 확인 중인 주문은 취소할 수 없습니다. 결제 대사 후 다시 시도해주세요.");
            default -> throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS,
                    "이미 %s 상태인 주문입니다.".formatted(snapshot.getStatus()));
        };
    }

    private OrderStatus cancelPaid(Long orderId, TransactionTemplate tx) {
        Payment approved = paymentRepository.findByOrderIdOrderByIdDesc(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.APPROVED)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ORDER_STATUS, "승인된 결제를 찾을 수 없습니다."));

        PgClient.PgResult refund = pgClient.cancel(approved.getPaymentKey());
        if (refund.outcome() != Outcome.CANCELLED) {
            log.warn("event=PG_CANCEL_FAILED paymentKey={} outcome={} reason={}",
                    approved.getPaymentKey(), refund.outcome(), refund.reason());
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }

        return tx.execute(s -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            Payment payment = paymentRepository.findById(approved.getId()).orElseThrow();
            payment.cancel();
            order.cancel();
            orderService.restoreStock(order);
            log.info("event=ORDER_CANCELLED orderNo={} from=PAID refund={}", order.getOrderNo(), payment.getPaymentKey());
            return order.getStatus();
        });
    }
}
