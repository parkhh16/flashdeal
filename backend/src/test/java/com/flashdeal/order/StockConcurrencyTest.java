package com.flashdeal.order;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.product.Product;
import com.flashdeal.product.stock.StockService;
import com.flashdeal.product.stock.StockStrategyType;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 100개 상품에 1,000명이 동시에 1개씩 주문한다. 전략별로 정합성과 처리 시간을 비교한다.
 * 정합성 기준: (성공 주문 수 + 남은 재고) == 초기 재고. 깨지면 오버셀링(팔린 것보다 재고가 덜 줄어듦)이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestFixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockConcurrencyTest {

    private static final int INITIAL_STOCK = 100;
    private static final int REQUESTS = 1_000;
    private static final int THREADS = 64;

    @Autowired OrderFacade orderFacade;
    @Autowired StockService stockService;
    @Autowired TestFixtures fixtures;

    private final Map<StockStrategyType, String> report = new ConcurrentSkipListMap<>();

    @BeforeEach
    void setUp() {
        fixtures.clean();
    }

    @DisplayName("재고 100개, 1000명 동시 주문 - 전략별 결과")
    @ParameterizedTest(name = "{0}")
    @EnumSource(StockStrategyType.class)
    void concurrentOrders(StockStrategyType strategy) throws Exception {
        stockService.changeStrategy(strategy);
        Product product = fixtures.product(INITIAL_STOCK);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger outOfStock = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            long userId = i + 1;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    orderFacade.create(userId, new CreateOrderRequest(
                            List.of(new CreateOrderRequest.Line(product.getId(), 1))));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    switch (e.getErrorCode()) {
                        case OUT_OF_STOCK -> outOfStock.incrementAndGet();
                        case STOCK_CONFLICT -> conflict.incrementAndGet();
                        default -> error.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                }
                return null;
            }));
        }
        long begin = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) f.get();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdown();

        int remaining = fixtures.stockOf(product.getId());
        int oversold = success.get() + remaining - INITIAL_STOCK;
        report.put(strategy, "| %-13s | %4d | %5d | %8d | %5d | %9d | %6d | %7.0f |".formatted(
                strategy, success.get(), remaining, oversold, outOfStock.get(), conflict.get() + error.get(),
                elapsedMs, REQUESTS * 1000.0 / elapsedMs));

        if (strategy == StockStrategyType.NAIVE) {
            // 문제 재현: 락이 없으면 재고 100개보다 많이 팔린다 (Lost Update)
            assertThat(oversold).as("NAIVE는 오버셀링이 발생해야 한다").isPositive();
        } else {
            assertThat(oversold).as("오버셀링 0건").isZero();
            assertThat(success.get()).isLessThanOrEqualTo(INITIAL_STOCK);
            if (strategy != StockStrategyType.OPTIMISTIC) {
                assertThat(success.get()).isEqualTo(INITIAL_STOCK);
                assertThat(remaining).isZero();
            }
        }
    }

    @AfterAll
    void printReport() {
        System.out.println();
        System.out.println("===== 재고 " + INITIAL_STOCK + "개 / 동시 요청 " + REQUESTS + "건 / 스레드 " + THREADS + " =====");
        System.out.println("| 전략          | 성공 | 잔여 | 오버셀링 | 품절 | 충돌/에러 | 소요ms | req/s   |");
        System.out.println("|---------------|------|------|----------|------|-----------|--------|---------|");
        report.values().forEach(System.out::println);
        System.out.println();
    }
}
