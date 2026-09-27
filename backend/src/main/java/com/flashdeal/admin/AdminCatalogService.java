package com.flashdeal.admin;

import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.Order;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.payment.Payment;
import com.flashdeal.payment.PaymentRepository;
import com.flashdeal.payment.PaymentStatus;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminCatalogService {

    private static final java.util.Set<OrderStatus> HOLDING = java.util.EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYING);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public record ProductForm(@NotBlank @Size(max = 100) String name,
                              @NotNull SubCategory subCategory,
                              @Positive long price,
                              @Positive Long originalPrice,
                              @PositiveOrZero Integer stock,
                              @Size(max = 500) String description,
                              @Size(max = 2000) String detail,
                              LocalDateTime dealStartAt,
                              LocalDateTime dealEndAt,
                              @Positive Integer dealQuantity,
                              @Positive Integer perUserLimit) {
    }

    /** @param held 결제 대기·결제 확인 중 주문이 쥐고 있는 수량 (가용 재고 stock과 별개로 보여줘야 재고 조정 실수를 막는다) */
    public record AdminProduct(@JsonUnwrapped ProductResponse summary, boolean active, String detail, long held) {
    }

    public record AdminOrder(@JsonUnwrapped OrderResponse order, Long userId, String userName,
                             PaymentStatus paymentStatus) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    @Transactional(readOnly = true)
    public List<AdminProduct> products() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 상품별 선점 수량은 GROUP BY 한 번으로 (상품마다 합계 쿼리를 날리면 N+1)
        Map<Long, Long> held = orderRepository.sumHeldQuantityByProduct(HOLDING).stream()
                .collect(Collectors.toMap(OrderRepository.ProductHeld::getProductId, OrderRepository.ProductHeld::getQuantity));
        return productRepository.findAll(Sort.by("id")).stream().map(p -> toAdmin(p, now, held.getOrDefault(p.getId(), 0L))).toList();
    }

    @Transactional
    public AdminProduct create(ProductForm f) {
        Product product = Product.builder().name(f.name()).subCategory(f.subCategory()).price(f.price())
                .stock(f.stock() == null ? 0 : f.stock()).build();
        applyForm(product, f);
        return toAdmin(productRepository.save(product), LocalDateTime.now(clock));
    }

    @Transactional
    public AdminProduct update(Long id, ProductForm f) {
        Product product = find(id);
        applyForm(product, f);
        return toAdmin(product, LocalDateTime.now(clock));
    }

    @Transactional
    public AdminProduct changeActive(Long id, boolean active) {
        Product product = find(id);
        product.changeActive(active);
        return toAdmin(product, LocalDateTime.now(clock));
    }

    /** 주문 목록. items는 배치 페치, 사용자 이름과 결제 상태는 IN 쿼리 한 번씩 → 페이지당 쿼리 수 고정 */
    @Transactional(readOnly = true)
    public PageResponse<AdminOrder> orders(OrderStatus status, Long userId, int page, int size) {
        Specification<Order> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (userId != null) p.add(cb.equal(root.get("userId"), userId));
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<Order> orders = orderRepository.findAll(spec, PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "id")));
        Set<Long> userIds = orders.stream().map(Order::getUserId).collect(Collectors.toSet());
        Map<Long, String> names = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
        Map<Long, PaymentStatus> payments = paymentRepository.findByOrderIdIn(orders.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.toMap(Payment::getOrderId, Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b)) // 주문당 가장 최근 결제 시도
                .values().stream().collect(Collectors.toMap(Payment::getOrderId, Payment::getStatus));
        List<AdminOrder> content = orders.stream().map(o -> new AdminOrder(OrderResponse.from(o), o.getUserId(),
                names.get(o.getUserId()), payments.get(o.getId()))).toList();
        return new PageResponse<>(content, orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages());
    }

    private void applyForm(Product product, ProductForm f) {
        boolean deal = f.dealEndAt() != null;
        product.update(f.name(), f.subCategory(), f.price(), f.originalPrice(), f.description(), f.detail(),
                deal ? f.dealStartAt() : null, f.dealEndAt(), deal ? f.dealQuantity() : null, f.perUserLimit());
    }

    private Product find(Long id) {
        return productRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    private AdminProduct toAdmin(Product p, LocalDateTime now) {
        return toAdmin(p, now, orderRepository.sumHeldQuantity(p.getId(), HOLDING));
    }

    private AdminProduct toAdmin(Product p, LocalDateTime now, long held) {
        return new AdminProduct(ProductResponse.from(p, now), p.isActive(), p.getDetail(), held);
    }
}
