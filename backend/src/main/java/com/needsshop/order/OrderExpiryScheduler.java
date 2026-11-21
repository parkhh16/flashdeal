package com.needsshop.order;

import com.needsshop.common.cluster.ClusterLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제하지 않은 주문의 재고 선점을 풀어준다.
 * 한 건이 실패해도 나머지는 처리되도록 주문마다 트랜잭션을 따로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryScheduler {

    private static final int CHUNK = 100;

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ClusterLock clusterLock;
    private final Clock clock;

    /**
     * 서버가 여러 대면 한 서버에서만 실행한다. 락이 없어도 주문의 @Version 덕분에 재고가 두 번 복구되지는 않지만,
     * 서버마다 같은 주문을 동시에 처리하다 낙관적 락 충돌만 쌓인다.
     */
    @Scheduled(fixedDelayString = "${needsshop.scheduler.expiry-interval:5s}")
    public void scheduledExpiry() {
        clusterLock.runExclusively("order-expiry", Duration.ofMinutes(1), this::expireOverdueOrders);
    }

    public int expireOverdueOrders() {
        List<Long> ids = orderRepository.findIdsToExpire(OrderStatus.PENDING_PAYMENT,
                LocalDateTime.now(clock), PageRequest.of(0, CHUNK));
        int expired = 0;
        for (Long id : ids) {
            try {
                if (orderService.expireAndRestore(id)) {
                    expired++;
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                // 같은 순간 사용자가 결제를 시작했다. 결제 쪽이 이겼으므로 만료하지 않는다.
                log.info("event=EXPIRE_SKIPPED_BY_CONCURRENT_PAYMENT orderId={}", id);
            }
        }
        if (expired > 0) {
            log.info("event=EXPIRY_BATCH expired={}", expired);
        }
        return expired;
    }
}
