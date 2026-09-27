package com.flashdeal.admin;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderCancelService;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.order.OrderFacade;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import com.flashdeal.payment.PaymentRepository;
import com.flashdeal.payment.PaymentService;
import com.flashdeal.payment.PaymentStatus;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductDtos.SearchCondition;
import com.flashdeal.product.ProductQueryService;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFixtures.class)
class AdminFeaturesTest {

    @Autowired OrderFacade orderFacade;
    @Autowired OrderCancelService cancelService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductQueryService productQueryService;
    @Autowired ConcurrencyTestTool concurrencyTestTool;
    @Autowired AdminStockService adminStockService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestFixtures fixtures;

    private Product product;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        product = fixtures.product(10);
    }

    private OrderResponse order(int qty) {
        return orderFacade.create(1L, new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), qty))));
    }

    @Test
    @DisplayName("결제 대기 주문 취소: CANCELLED + 재고 복구")
    void cancelPending() {
        OrderResponse o = order(3);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(7);

        assertThat(cancelService.cancel(o.id())).isEqualTo(OrderStatus.CANCELLED);

        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("결제 완료 주문 취소: PG 환불 → 결제 CANCELLED, 주문 CANCELLED, 재고 복구, 정합성 불일치 없음. 두 번 취소는 불가")
    void cancelPaidRefunds() {
        OrderResponse o = order(2);
        paymentService.pay(1L, o.id());

        cancelService.cancel(o.id());

        assertThat(orderRepository.findById(o.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderIdOrderByIdDesc(o.id()).get(0).getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
        assertThat(paymentRepository.findMismatches()).isEmpty();
        assertThatThrownBy(() -> cancelService.cancel(o.id()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
    }

    @Test
    @DisplayName("결제 확인 중(PAYING) 주문은 취소할 수 없다")
    void cannotCancelPaying() {
        OrderResponse o = order(1);
        jdbc.update("update orders set status = 'PAYING' where id = ?", o.id());

        assertThatThrownBy(() -> cancelService.cancel(o.id()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ORDER_STATUS);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(9);
    }

    @Test
    @DisplayName("동시성 테스트 도구: 재고 50에 150건 → 성공 50 / 품절 100 / 최종 재고 0, 임시 데이터는 정리된다")
    void concurrencyTool() {
        long productsBefore = productRepository.count();

        ConcurrencyTestTool.Result r = concurrencyTestTool.run(50, 150);

        assertThat(r.success()).isEqualTo(50);
        assertThat(r.outOfStock()).isEqualTo(100);
        assertThat(r.finalStock()).isZero();
        assertThat(r.consistent()).isTrue();
        assertThat(productRepository.count()).isEqualTo(productsBefore);
        assertThat(fixtures.count("orders")).isZero();
    }

    @Test
    @DisplayName("판매 중지 상품: 목록/상세에서 숨고, 사용자 주문도 불가")
    void inactiveProduct() {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                productRepository.findById(product.getId()).orElseThrow().changeActive(false));

        assertThat(productQueryService.search(new SearchCondition(null, null, null, null, null, false, null)))
                .noneMatch(p -> p.id().equals(product.getId()));
        assertThatThrownBy(() -> productQueryService.detail(product.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> order(1))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("재고 조정은 증감이다: 결제 대기 주문이 쥔 수량이 나중에 돌아와도 실물 수(초기 10 + 입고 5 = 15)와 어긋나지 않는다")
    void stockAdjustmentIsDelta() {
        OrderResponse pending = order(3);                     // 가용 7, 선점 3 (실물 10)
        AdminStockService.Result r = adminStockService.adjust(product.getId(), 5, "입고");
        assertThat(r.stock()).isEqualTo(12);
        assertThat(r.held()).isEqualTo(3);

        cancelService.cancel(pending.id());                   // 선점 3이 돌아옴
        assertThat(fixtures.stockOf(product.getId())).as("실물 15개 = 가용 15").isEqualTo(15);
        // 덮어쓰기였다면: 관리자가 실물 수 15를 입력 → stock = 15 → 선점 3이 돌아오며 18 → 실물보다 3개를 더 팔 수 있게 된다

        assertThatThrownBy(() -> adminStockService.adjust(product.getId(), -16, "출고"))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.OUT_OF_STOCK);
        assertThatThrownBy(() -> adminStockService.adjust(product.getId(), 0, "없음"))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(adminStockService.adjust(product.getId(), -15, "출고").stock()).isZero();
    }

    @Test
    @DisplayName("@DynamicUpdate: 관리자가 상품을 수정하는 사이 주문으로 재고가 줄어도, 수정 커밋이 재고를 되돌리지 않는다")
    void adminEditDoesNotOverwriteStock() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Product p = productRepository.findById(product.getId()).orElseThrow(); // 이 시점 stock=10을 읽음
            // 다른 트랜잭션(주문)이 재고를 3 줄이고 커밋
            CompletableFuture.runAsync(() -> jdbc.update("update product set stock = stock - 3 where id = ?", p.getId())).join();
            p.update("이름 변경", SubCategory.KEYBOARD_MECHANICAL, 12_000, null, "d", null, null, null, null, null);
        });

        Product after = productRepository.findById(product.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("이름 변경");
        assertThat(after.getStock()).as("변경 감지가 stock 컬럼까지 덮어쓰면 10으로 되돌아간다").isEqualTo(7);
    }
}
