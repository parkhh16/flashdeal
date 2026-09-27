package com.flashdeal.payment;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.mockpg.ChaosSettings;
import com.flashdeal.payment.PgClient.Outcome;
import com.flashdeal.payment.PgClient.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 흐름: [TX1 결제 시도 기록] → PG 호출(트랜잭션 없음) → [TX2 결과 반영]
 * TX2가 실패해도(서버 다운, DB 장애) TX1에서 남긴 REQUESTED 기록이 있으므로 대사 스케줄러가 PG에 조회해서 복구한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentTxService txService;
    private final PgClient pgClient;
    private final ChaosSettings chaos;

    public record PaymentResponse(Long orderId, String paymentKey, PaymentStatus status, String message) {
    }

    public PaymentResponse pay(Long userId, Long orderId) {
        PaymentTxService.Prepared prepared = txService.prepare(userId, orderId);

        PgResult result = pgClient.approve(prepared.paymentKey(), prepared.orderNo(), prepared.amount());

        if (result.outcome() == Outcome.APPROVED && chaos.get().failAfterPgApproval()) {
            // 장애 주입: PG는 승인했는데 우리 DB에 반영하기 직전에 서버가 죽은 상황
            throw new IllegalStateException("chaos: DB failure after PG approval. paymentKey=" + prepared.paymentKey());
        }

        PaymentStatus status = txService.complete(prepared.paymentId(), result);
        return switch (status) {
            case APPROVED -> new PaymentResponse(orderId, prepared.paymentKey(), status, "결제가 완료되었습니다.");
            case FAILED -> throw new BusinessException(ErrorCode.PAYMENT_DECLINED,
                    "결제가 거절되었습니다. 사유: " + result.reason());
            default -> new PaymentResponse(orderId, prepared.paymentKey(), status,
                    "결제 결과를 확인 중입니다. 잠시 후 주문 상태를 확인해주세요.");
        };
    }
}
