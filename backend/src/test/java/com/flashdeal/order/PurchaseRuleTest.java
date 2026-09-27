package com.flashdeal.order;

import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.product.Product;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestFixtures.class)
class PurchaseRuleTest {

    @Autowired OrderFacade orderFacade;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        userId = userRepository.save(new User(UUID.randomUUID() + "@t.com", "x", "tester", User.Role.USER)).getId();
    }

    private Product deal(LocalDateTime start, LocalDateTime end, Integer limit, int stock) {
        return productRepository.save(Product.builder().name("특가").subCategory(SubCategory.MOUSE_WIRELESS)
                .price(10_000).originalPrice(20_000L).stock(stock).dealQuantity(stock)
                .dealStartAt(start).dealEndAt(end).perUserLimit(limit).build());
    }

    private void order(Long productId, int qty) {
        orderFacade.create(userId, new CreateOrderRequest(List.of(new CreateOrderRequest.Line(productId, qty))));
    }

    private ErrorCode codeOf(Runnable r) {
        try {
            r.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }

    @Test
    @DisplayName("1인 2개 제한: 2개까지는 성공, 이후 추가 주문은 거절")
    void limit() {
        LocalDateTime now = LocalDateTime.now();
        Product p = deal(now.minusHours(1), now.plusHours(1), 2, 100);

        order(p.getId(), 2);

        assertThat(codeOf(() -> order(p.getId(), 1))).isEqualTo(ErrorCode.PURCHASE_LIMIT_EXCEEDED);
        assertThat(fixtures.stockOf(p.getId())).isEqualTo(98);
    }

    @Test
    @DisplayName("만료된 주문은 한도에서 제외된다 (재고를 돌려받았으므로)")
    void expiredOrdersDoNotCount() {
        LocalDateTime now = LocalDateTime.now();
        Product p = deal(now.minusHours(1), now.plusHours(1), 1, 100);
        order(p.getId(), 1);
        jdbc.update("update orders set status = 'EXPIRED' where user_id = ?", userId);

        order(p.getId(), 1);

        assertThat(fixtures.count("orders")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자가 1개씩 10번 동시에 주문해도 한도(2개)만큼만 성공한다")
    void concurrentSameUser() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Product p = deal(now.minusHours(1), now.plusHours(1), 2, 100);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                ErrorCode code = codeOf(() -> order(p.getId(), 1));
                if (code == null) success.incrementAndGet();
                else if (code == ErrorCode.PURCHASE_LIMIT_EXCEEDED) limited.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(success.get()).isEqualTo(2);
        assertThat(limited.get()).isEqualTo(8);
        assertThat(fixtures.stockOf(p.getId())).isEqualTo(98);
    }

    @Test
    @DisplayName("오픈 전 / 종료된 특가는 주문할 수 없고 재고도 줄지 않는다")
    void salePeriod() {
        LocalDateTime now = LocalDateTime.now();
        Product upcoming = deal(now.plusHours(1), now.plusHours(2), null, 10);
        Product ended = deal(now.minusHours(2), now.minusHours(1), null, 10);

        assertThat(codeOf(() -> order(upcoming.getId(), 1))).isEqualTo(ErrorCode.DEAL_NOT_OPEN);
        assertThat(codeOf(() -> order(ended.getId(), 1))).isEqualTo(ErrorCode.DEAL_ENDED);
        assertThat(fixtures.stockOf(upcoming.getId())).isEqualTo(10);
        assertThat(fixtures.stockOf(ended.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("존재하지 않는 상품")
    void notFound() {
        assertThatThrownBy(() -> order(999_999L, 1))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }
}
