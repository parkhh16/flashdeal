package com.flashdeal.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * 주문 상태 머신. 허용된 전이만 가능하므로 "만료된 주문이 결제됨" 같은 불가능한 상태를 코드 레벨에서 차단한다.
 *
 * <pre>
 * PENDING_PAYMENT ──결제 시작──▶ PAYING ──승인──▶ PAID
 *       │    ▲                     │
 *       │    └──────거절(재결제 가능)─┤
 *       ├──TTL 만료──▶ EXPIRED ◀────┘ 거절 & 기한 초과
 *       └──관리자 취소──▶ CANCELLED ◀── PAID (PG 환불 성공 시)
 * </pre>
 * PAYING 상태는 만료 스케줄러가 건드리지 않는다. 결제 결과를 모르는 상태에서 재고를 풀면
 * "돈은 빠졌는데 재고는 다른 사람에게 팔린" 상황이 생기기 때문에, 대사(Reconcile)로만 빠져나간다.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAYING,
    PAID,
    EXPIRED,
    CANCELLED;

    private Set<OrderStatus> next;

    static {
        PENDING_PAYMENT.next = EnumSet.of(PAYING, EXPIRED, CANCELLED);
        PAYING.next = EnumSet.of(PAID, PENDING_PAYMENT, EXPIRED);
        PAID.next = EnumSet.of(CANCELLED); // 관리자 취소(PG 환불 후)
        EXPIRED.next = EnumSet.noneOf(OrderStatus.class);
        CANCELLED.next = EnumSet.noneOf(OrderStatus.class);
    }

    public boolean canTransitTo(OrderStatus target) {
        return next.contains(target);
    }

    /** 재고를 계속 점유하고 있는 상태인지 여부 */
    public boolean holdsStock() {
        return this == PENDING_PAYMENT || this == PAYING || this == PAID;
    }
}
