package com.flashdeal.common.config;

import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 시연용: 끝난 시드 특가를 같은 시간표로 다시 편성한다.
 * 시드 특가는 "기동 시각 기준 상대 시간"이라 H2(재시작마다 초기화)에서는 항상 살아 있지만,
 * MySQL처럼 데이터가 남는 DB에서는 하루가 지나면 전부 종료되어 "오늘의 특가"가 비어 버린다.
 *
 * 운영 기능이 아니라 데모 데이터 유지용이라 설정으로 끈다 (flashdeal.demo.rotate-deals=false).
 * 그래도 재고를 건드리는 작업이므로 안전 조건은 지킨다.
 * - 끝난 지 15분(결제 대기 TTL 10분보다 길게)이 지난 특가만: 종료 직전에 검증을 통과한 주문의 차감이 끝났음을 보장
 * - 결제 대기·결제 확인 중 주문이 재고를 쥐고 있으면 건너뜀: 새 재고로 덮어쓴 뒤 그 주문이 만료되며 재고를 되돌리면
 *   한정 수량보다 많아지기 때문 (관리자 재고 "덮어쓰기"로 실제로 겪은 문제)
 * - UPDATE ... WHERE deal_end_at < ? 조건부 갱신이라 중복 실행돼도 한 번만 반영
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flashdeal.demo.rotate-deals", havingValue = "true")
public class DemoDealRotator {

    static final Duration GRACE = Duration.ofMinutes(15);
    private static final Set<OrderStatus> HOLDING = EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYING);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DemoDealRotator(ProductRepository productRepository, OrderRepository orderRepository,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 기동 직후 한 번 (시드가 끝난 뒤 실행된다: ApplicationRunner → ApplicationReadyEvent 순서) */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        rotate();
    }

    @Scheduled(fixedDelayString = "5m", initialDelayString = "5m")
    public int rotate() {
        LocalDateTime now = LocalDateTime.now(clock);
        int rotated = 0;
        for (Map.Entry<String, DemoDeals.Slot> entry : DemoDeals.SLOTS.entrySet()) {
            Optional<Product> found = productRepository.findFirstByNameOrderByIdAsc(entry.getKey());
            if (found.isEmpty() || found.get().getDealEndAt() == null) continue;
            Product product = found.get();
            if (product.getDealEndAt().isAfter(now.minus(GRACE))) continue; // 아직 진행 중이거나 막 끝남

            long held = orderRepository.sumHeldQuantity(product.getId(), HOLDING);
            if (held > 0) {
                log.info("event=DEMO_DEAL_ROTATE_SKIPPED product={} heldByUnpaidOrders={}", product.getId(), held);
                continue;
            }
            DemoDeals.Slot slot = entry.getValue();
            Integer updated = tx.execute(s -> productRepository.rescheduleEndedDeal(
                    product.getId(), slot.start(now), slot.end(now), slot.stock(), now.minus(GRACE)));
            if (updated != null && updated > 0) {
                rotated++;
                log.info("event=DEMO_DEAL_ROTATED product={} start={} end={} stock={}",
                        product.getId(), slot.start(now), slot.end(now), slot.stock());
            }
        }
        return rotated;
    }
}
