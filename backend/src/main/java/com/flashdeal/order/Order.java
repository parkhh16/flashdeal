package com.flashdeal.order;

import com.flashdeal.common.BaseTimeEntity;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "orders", indexes = {
        // 내 주문 목록: WHERE user_id = ? ORDER BY id DESC 를 인덱스만으로 정렬까지 처리
        @Index(name = "idx_orders_user_id_id", columnList = "user_id, id"),
        // 만료 스케줄러: WHERE status = 'PENDING_PAYMENT' AND expires_at < ?
        @Index(name = "idx_orders_status_expires_at", columnList = "status, expires_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private long totalAmount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 만료 스케줄러와 결제 요청이 같은 주문의 상태를 동시에 바꾸려 할 때,
     * 먼저 커밋한 쪽만 성공하도록 낙관적 락을 건다. 충돌이 드문 곳이라 낙관적 락이 적합하다.
     */
    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public static Order create(Long userId, LocalDateTime expiresAt) {
        Order order = new Order();
        order.orderNo = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        order.userId = userId;
        order.status = OrderStatus.PENDING_PAYMENT;
        order.expiresAt = expiresAt;
        return order;
    }

    public void addItem(Long productId, String productName, long unitPrice, int quantity) {
        items.add(new OrderItem(this, productId, productName, unitPrice, quantity));
        totalAmount += unitPrice * quantity;
    }

    public void startPayment(LocalDateTime now) {
        if (status == OrderStatus.PENDING_PAYMENT && isOverdue(now)) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED);
        }
        transitTo(OrderStatus.PAYING);
    }

    public void markPaid() {
        transitTo(OrderStatus.PAID);
    }

    /** 결제 거절. 기한이 남아 있으면 재결제할 수 있게 되돌리고, 지났으면 만료 처리한다 */
    public void paymentFailed(LocalDateTime now) {
        transitTo(isOverdue(now) ? OrderStatus.EXPIRED : OrderStatus.PENDING_PAYMENT);
    }

    public void cancel() {
        transitTo(OrderStatus.CANCELLED);
    }

    public void expire() {
        transitTo(OrderStatus.EXPIRED);
    }

    public boolean isOverdue(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    private void transitTo(OrderStatus target) {
        if (!status.canTransitTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS,
                    "주문 상태를 %s에서 %s(으)로 바꿀 수 없습니다.".formatted(status, target));
        }
        this.status = target;
    }
}
