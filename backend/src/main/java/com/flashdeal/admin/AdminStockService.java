package com.flashdeal.admin;

import com.flashdeal.activity.ActivityContext;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * 관리자 재고 조정은 "덮어쓰기"가 아니라 "증감(입고 +N / 출고 -N)"으로 한다.
 *
 * 덮어쓰기(stock = N)의 문제 (MySQL 데모 데이터에서 실제로 발생):
 * product.stock은 "지금 팔 수 있는 가용 재고"이고, 결제 대기 주문이 쥔 수량은 이미 빠져 있다.
 * 관리자가 이를 모르고 stock = 2000으로 덮어쓰면, 나중에 그 주문들이 만료되며 쥐고 있던 수량을 다시 더한다.
 * → 한정 100개 상품의 재고가 16,602개까지 부풀었다 (실물보다 많이 팔 수 있는 상태).
 * 증감 방식은 "실물이 N개 들어왔다/나갔다"만 반영하므로, 쥐고 있던 수량이 돌아와도 실물 수와 어긋나지 않는다.
 * 잔액을 balance = balance + amount 로 갱신하는 것과 같은 원리다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminStockService {

    private static final Set<OrderStatus> HOLDING = EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYING);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    /** @param stock 조정 후 가용 재고, @param held 결제 대기·결제 확인 중 주문이 쥐고 있는 수량 */
    public record Result(Long productId, int delta, int stock, long held) {
    }

    @Transactional
    public Result adjust(Long productId, int delta, String reason) {
        if (delta == 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "0이 아닌 수량을 입력해주세요. (입고는 +, 출고는 -)");
        }
        if (!productRepository.existsById(productId)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        // 원자적 증감: 같은 순간 들어온 주문의 차감과 섞여도 둘 다 반영된다 (읽고-계산하고-쓰기 없음)
        if (productRepository.adjustStock(productId, delta) == 0) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK, "가용 재고보다 많이 출고할 수 없습니다.");
        }
        int stock = productRepository.findStockById(productId);
        long held = orderRepository.sumHeldQuantity(productId, HOLDING);
        ActivityContext.put("delta", delta);
        ActivityContext.put("reason", reason);
        log.info("event=STOCK_ADJUSTED productId={} delta={} stock={} held={} reason={}", productId, delta, stock, held, reason);
        return new Result(productId, delta, stock, held);
    }
}
