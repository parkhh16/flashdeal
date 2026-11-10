package com.flashdeal.payment;

import com.flashdeal.common.cluster.ClusterLock;
import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.payment.PgClient.Outcome;
import com.flashdeal.payment.PgClient.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * 결제 대사(Reconciliation). "PG는 승인했는데 우리 DB는 모르는" 불일치를 PG를 기준으로 맞춘다.
 * 대상: REQUESTED(TX2 전에 죽음), UNKNOWN(타임아웃)이 된 지 grace 시간 이상 지난 결제.
 * grace를 두는 이유는 아직 PG에 요청이 가는 중인 건을 NOT_FOUND로 오판하지 않기 위해서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciler {

    private final PaymentRepository paymentRepository;
    private final PaymentTxService txService;
    private final PgClient pgClient;
    private final FlashDealProperties properties;
    private final ClusterLock clusterLock;
    private final Clock clock;

    public record Result(int checked, int approved, int failed, int stillUnknown) {
    }

    /**
     * 서버가 여러 대면 한 서버에서만 실행한다. 서버마다 대사를 돌리면 같은 결제를 PG에 여러 번 조회하게 된다.
     * PG 조회 한 건이 최대 수 초라 100건이면 몇 분이 걸릴 수 있어, 락 유지 시간을 넉넉하게 둔다.
     */
    @Scheduled(fixedDelayString = "${flashdeal.scheduler.reconcile-interval:10s}", initialDelayString = "10s")
    public void scheduledReconcile() {
        clusterLock.runExclusively("payment-reconcile", Duration.ofMinutes(5), this::reconcile);
    }

    public Result reconcile() {
        LocalDateTime before = LocalDateTime.now(clock).minus(properties.payment().reconcileGrace());
        List<Payment> targets = paymentRepository.findPending(
                EnumSet.of(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN), before, PageRequest.of(0, 100));

        int approved = 0, failed = 0, unknown = 0;
        for (Payment payment : targets) {
            PgResult result = pgClient.inquire(payment.getPaymentKey());
            if (result.outcome() == Outcome.UNKNOWN) {
                unknown++; // PG도 응답이 없으면 다음 주기에 다시 확인한다
                continue;
            }
            try {
                PaymentStatus status = txService.complete(payment.getId(), result);
                if (status == PaymentStatus.APPROVED) approved++;
                else if (status == PaymentStatus.FAILED) failed++;
                log.info("event=PAYMENT_RECONCILED paymentKey={} pg={} result={}",
                        payment.getPaymentKey(), result.outcome(), status);
            } catch (RuntimeException e) {
                log.error("event=RECONCILE_FAILED paymentKey={}", payment.getPaymentKey(), e);
            }
        }
        if (!targets.isEmpty()) {
            log.info("event=RECONCILE_BATCH checked={} approved={} failed={} unknown={}",
                    targets.size(), approved, failed, unknown);
        }
        return new Result(targets.size(), approved, failed, unknown);
    }
}
