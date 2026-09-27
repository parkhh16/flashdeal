package com.flashdeal.payment;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.mockpg.ChaosSettings;
import com.flashdeal.mockpg.ChaosSettings.Snapshot;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderFacade;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.product.Product;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 실제 HTTP로 Mock PG를 호출하면서 장애를 주입하고, 최종 상태가 PG와 일치하는지 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFixtures.class)
class PaymentFailureScenarioTest {

    private static final long USER = 1L;

    @Autowired OrderFacade orderFacade;
    @Autowired PaymentService paymentService;
    @Autowired PaymentReconciler reconciler;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ChaosSettings chaos;
    @Autowired PgClient pgClient;
    @Autowired TestFixtures fixtures;

    private Product product;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        chaos.set(new Snapshot(0, 0, 0, 0, false));
        product = fixtures.product(10);
    }

    @AfterEach
    void tearDown() {
        chaos.reset();
    }

    @Test
    @DisplayName("정상 결제: 주문 PAID, 결제 APPROVED")
    void approve() {
        OrderResponse order = order();

        PaymentService.PaymentResponse res = paymentService.pay(USER, order.id());

        assertThat(res.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 거절: 주문은 PENDING_PAYMENT로 돌아가고 재결제하면 성공한다 (재고는 계속 점유)")
    void declineThenRetry() {
        OrderResponse order = order();
        chaos.set(new Snapshot(0, 0, 1.0, 0, false));

        assertThatThrownBy(() -> paymentService.pay(USER, order.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(9);

        chaos.set(new Snapshot(0, 0, 0, 0, false));
        assertThat(paymentService.pay(USER, order.id()).status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(paymentRepository.findByOrderIdOrderByIdDesc(order.id())).hasSize(2);
    }

    @Test
    @DisplayName("PG 응답 타임아웃: 같은 paymentKey로 재시도하면 PG 멱등성 덕분에 이중 결제 없이 승인된다")
    void timeoutThenRetryIsIdempotent() {
        OrderResponse order = order();
        // PG는 승인해놓고 3초 뒤에 응답한다. 클라이언트 read-timeout은 1초
        chaos.set(new Snapshot(0, 0, 0, 1.0, false));

        PaymentService.PaymentResponse res = paymentService.pay(USER, order.id());

        assertThat(res.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(paymentRepository.findByOrderIdOrderByIdDesc(order.id())).hasSize(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("PG 승인 직후 우리 서버 장애: 결제 REQUESTED로 남고, 대사 배치가 PG 기준으로 PAID로 복구한다")
    void approvedAtPgButDbFailed() {
        OrderResponse order = order();
        chaos.set(new Snapshot(0, 0, 0, 0, true));

        assertThatThrownBy(() -> paymentService.pay(USER, order.id())).isInstanceOf(IllegalStateException.class);

        // 불일치 상태: PG는 승인, 우리는 모름
        Payment payment = paymentRepository.findByOrderIdOrderByIdDesc(order.id()).get(0);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(pgClient.inquire(payment.getPaymentKey()).outcome()).isEqualTo(PgClient.Outcome.APPROVED);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYING);

        // 같은 주문으로 다시 결제를 시도해도 PAYING 상태라 막힌다 → 이중 결제 방지
        assertThatThrownBy(() -> paymentService.pay(USER, order.id()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS);

        PaymentReconciler.Result result = reconciler.reconcile();

        assertThat(result.approved()).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(paymentRepository.findMismatches()).isEmpty();
    }

    @Test
    @DisplayName("PG 장애(5xx 지속): 결과 UNKNOWN(202)으로 두고, 대사에서 PG에 기록이 없으면 실패 처리 후 재결제 가능")
    void pgDownThenReconcileAsNotFound() {
        OrderResponse order = order();
        chaos.set(new Snapshot(0, 1.0, 0, 0, false));

        PaymentService.PaymentResponse res = paymentService.pay(USER, order.id());
        assertThat(res.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYING);

        chaos.set(new Snapshot(0, 0, 0, 0, false));
        PaymentReconciler.Result result = reconciler.reconcile();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(paymentService.pay(USER, order.id()).status()).isEqualTo(PaymentStatus.APPROVED);
    }

    private OrderResponse order() {
        return orderFacade.create(USER, new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), 1))));
    }

    private OrderStatus statusOf(OrderResponse order) {
        return orderRepository.findById(order.id()).orElseThrow().getStatus();
    }
}
