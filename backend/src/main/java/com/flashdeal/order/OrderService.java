package com.flashdeal.order;

import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderDtos.PageResponse;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.PurchaseRule;
import com.flashdeal.product.stock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Set<OrderStatus> STOCK_HOLDING_STATUSES =
            EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYING, OrderStatus.PAID);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final StockService stockService;
    private final FlashDealProperties properties;
    private final Clock clock;

    /**
     * 주문 생성 = 재고 선점. 주문 시점에 재고를 차감하고, TTL 안에 결제하지 않으면 만료 스케줄러가 복구한다.
     * (결제 시점 차감은 결제 완료 후에야 품절을 알게 되고, 이미 돈을 낸 사용자에게 환불해야 하는 문제가 있다)
     *
     * 격리 수준을 READ COMMITTED로 명시한 이유 (MySQL에서 실제로 재현한 버그):
     * MySQL 기본값인 REPEATABLE READ는 트랜잭션의 "첫 번째 읽기" 시점 스냅샷을 끝까지 쓴다.
     * 1인 구매 제한은 [규칙 조회(스냅샷 고정) → 사용자 행 락 → 구매 수량 조회] 순서인데,
     * 락을 얻은 뒤에 읽어도 고정된 옛 스냅샷을 보기 때문에 앞 사람이 방금 커밋한 주문이 안 보인다.
     * → 한도 2개인데 동시 요청 10개 중 4개가 성공했다. READ COMMITTED는 읽을 때마다 최신 커밋을 본다.
     * (H2는 매 문장마다 최신 커밋을 봐서 이 문제가 드러나지 않았다)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse create(Long userId, CreateOrderRequest request) {
        return create(userId, request, false);
    }

    /**
     * @param allowInactive 관리자 동시성 테스트 도구 전용. 판매 중지 상태의 테스트 상품으로 주문해서
     *                      실험 중에도 실제 상품 목록에는 노출되지 않게 한다. 사용자 API에서는 항상 false
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse create(Long userId, CreateOrderRequest request, boolean allowInactive) {
        // 같은 상품이 여러 줄이면 합친다. productId 오름차순으로 락을 잡아서
        // 두 주문이 서로 다른 순서로 행 락을 잡다가 생기는 데드락을 예방한다.
        Map<Long, Integer> lines = new TreeMap<>();
        request.items().forEach(l -> lines.merge(l.productId(), l.quantity(), Integer::sum));

        LocalDateTime now = LocalDateTime.now(clock);
        validatePurchaseRules(userId, lines, now, allowInactive);

        Order order = Order.create(userId, now.plus(properties.order().reservationTtl()));
        lines.forEach((productId, quantity) -> {
            Product product = stockService.decrease(productId, quantity);
            order.addItem(productId, product.getName(), product.getPrice(), quantity);
        });
        orderRepository.save(order);

        log.info("event=ORDER_CREATED orderNo={} userId={} amount={} strategy={}",
                order.getOrderNo(), userId, order.getTotalAmount(), stockService.currentStrategy());
        return OrderResponse.from(order);
    }

    /**
     * 판매 기간과 1인 구매 제한을 재고 차감 "전에" 검증한다.
     * 1인 제한은 "조회 → 비교"라서 같은 사용자가 동시에 주문하면 둘 다 통과할 수 있다(check-then-act 경쟁).
     * 사용자 행에 락을 걸어 같은 사용자의 주문만 줄 세운다. 락 범위가 상품이 아니라 사용자라서 핫스팟이 생기지 않고,
     * 락 순서가 항상 사용자 → 상품(id 오름차순)이라 데드락도 없다.
     */
    private void validatePurchaseRules(Long userId, Map<Long, Integer> lines, LocalDateTime now, boolean allowInactive) {
        List<PurchaseRule> rules = productRepository.findPurchaseRules(lines.keySet());
        if (rules.size() != lines.size() || (!allowInactive && rules.stream().anyMatch(r -> !r.active()))) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        rules.forEach(rule -> rule.checkSalePeriod(now));

        List<PurchaseRule> limited = rules.stream().filter(PurchaseRule::limited).toList();
        if (limited.isEmpty()) {
            return;
        }
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        for (PurchaseRule rule : limited) {
            long held = orderRepository.sumQuantity(userId, rule.id(), STOCK_HOLDING_STATUSES);
            int requested = lines.get(rule.id());
            if (held + requested > rule.perUserLimit()) {
                throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED,
                        "'%s'은(는) 1인 %d개까지 구매할 수 있습니다. (이미 %d개 주문함)"
                                .formatted(rule.name(), rule.perUserLimit(), held));
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getMyOrders(Long userId, int page, int size) {
        Page<Order> orders = orderRepository.findByUserId(userId,
                PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "id")));
        return new PageResponse<>(orders.map(OrderResponse::from).getContent(),
                orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(Long userId, Long orderId) {
        return OrderResponse.from(findMyOrder(userId, orderId));
    }

    public Order findMyOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    /** 주문을 만료 처리하고 선점했던 재고를 되돌린다. 건마다 별도 트랜잭션으로 실행한다 */
    @Transactional
    public boolean expireAndRestore(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !order.isOverdue(LocalDateTime.now(clock))) {
            return false; // 조회 이후 결제가 시작되었으면 건너뛴다
        }
        order.expire();
        restoreStock(order);
        log.info("event=ORDER_EXPIRED orderNo={} restored={}", order.getOrderNo(), order.getItems().size());
        return true;
    }

    /** 결제 대사 등 다른 흐름에서 주문이 EXPIRED로 바뀌었을 때 재고를 복구한다 */
    @Transactional
    public void restoreStock(Order order) {
        order.getItems().forEach(item -> stockService.restore(item.getProductId(), item.getQuantity()));
    }
}
