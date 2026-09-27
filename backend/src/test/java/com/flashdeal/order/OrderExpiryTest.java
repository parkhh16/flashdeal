package com.flashdeal.order;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.payment.PaymentService;
import com.flashdeal.product.Product;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TTL을 음수로 줘서 주문 생성 즉시 결제 기한이 지난 상태를 만든다 */
@SpringBootTest(properties = "flashdeal.order.reservation-ttl=-1s")
@ActiveProfiles("test")
@Import(TestFixtures.class)
class OrderExpiryTest {

    @Autowired OrderFacade orderFacade;
    @Autowired OrderExpiryScheduler scheduler;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentService paymentService;
    @Autowired TestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures.clean();
    }

    @Test
    @DisplayName("미결제 주문은 만료되고 선점했던 재고가 복구된다")
    void expireRestoresStock() {
        Product product = fixtures.product(10);
        OrderDtos.OrderResponse order = orderFacade.create(1L,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), 3))));
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(7);

        int expired = scheduler.expireOverdueOrders();

        assertThat(expired).isEqualTo(1);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
        assertThat(scheduler.expireOverdueOrders()).as("두 번 실행해도 재고가 두 번 복구되지 않는다").isZero();
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("결제 기한이 지난 주문은 결제할 수 없다")
    void cannotPayExpiredOrder() {
        Product product = fixtures.product(10);
        OrderDtos.OrderResponse order = orderFacade.create(1L,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), 1))));

        assertThatThrownBy(() -> paymentService.pay(1L, order.id()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ORDER_EXPIRED);
    }
}
