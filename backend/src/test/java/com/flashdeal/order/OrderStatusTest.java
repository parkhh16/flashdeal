package com.flashdeal.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.flashdeal.order.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    @DisplayName("허용된 전이만 가능하다")
    void transitions() {
        assertThat(PENDING_PAYMENT.canTransitTo(PAYING)).isTrue();
        assertThat(PAYING.canTransitTo(PAID)).isTrue();
        assertThat(PAYING.canTransitTo(PENDING_PAYMENT)).isTrue();

        assertThat(PENDING_PAYMENT.canTransitTo(PAID)).as("결제 시작 없이 결제 완료 불가").isFalse();
        assertThat(EXPIRED.canTransitTo(PAYING)).as("만료된 주문은 결제 불가").isFalse();
        assertThat(PAID.canTransitTo(EXPIRED)).as("결제 완료 주문은 만료 불가").isFalse();
        assertThat(PAID.canTransitTo(PAID)).as("중복 결제 완료 불가").isFalse();
    }
}
