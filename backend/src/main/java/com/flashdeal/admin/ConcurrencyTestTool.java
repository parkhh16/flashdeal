package com.flashdeal.admin;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderFacade;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import com.flashdeal.product.SubCategory;
import com.flashdeal.product.stock.StockService;
import com.flashdeal.product.stock.StockStrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "재고 N개에 M명 동시 주문" 실험 도구.
 * 판매 중지 상태의 임시 상품을 만들어 실제 주문 경로(OrderFacade → OrderService → StockService)로 동시에 주문하고,
 * 성공/품절 건수와 최종 재고로 정합성(성공 + 남은 재고 == 초기 재고)을 검증한 뒤 임시 데이터를 지운다.
 * 현재 설정된 재고 전략으로 실행하므로, NAIVE로 바꾸고 돌리면 오버셀링을 눈으로 확인할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcurrencyTestTool {

    private static final long SYNTHETIC_USER_BASE = 9_000_000_000L; // 실제 사용자와 겹치지 않는 가상 사용자 ID
    private static final Semaphore ONE_AT_A_TIME = new Semaphore(1);

    private final OrderFacade orderFacade;
    private final ProductRepository productRepository;
    private final StockService stockService;
    private final JdbcTemplate jdbc;

    public record Result(StockStrategyType strategy, int initialStock, int requests, int success, int outOfStock,
                         int conflicts, int errors, int finalStock, int oversold, boolean consistent,
                         long elapsedMs, long throughput) {
    }

    public Result run(int stock, int requests) {
        if (stock < 1 || stock > 10_000 || requests < 1 || requests > 2_000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "재고는 1~10,000, 동시 요청은 1~2,000 사이로 입력해주세요.");
        }
        if (!ONE_AT_A_TIME.tryAcquire()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 실행 중인 동시성 테스트가 있습니다.");
        }
        Product product = Product.builder().name("[동시성 테스트] 재고 %d / 요청 %d".formatted(stock, requests))
                .subCategory(SubCategory.ACCESSORY_ETC).price(1_000).stock(stock).build();
        product.changeActive(false);
        productRepository.save(product);

        AtomicInteger success = new AtomicInteger(), outOfStock = new AtomicInteger(),
                conflicts = new AtomicInteger(), errors = new AtomicInteger();
        StockStrategyType strategy = stockService.currentStrategy();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(requests, 64));
        try {
            CountDownLatch start = new CountDownLatch(1);
            CreateOrderRequest request = new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), 1)));
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < requests; i++) {
                long userId = SYNTHETIC_USER_BASE + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        orderFacade.create(userId, request, true);
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        switch (e.getErrorCode()) {
                            case OUT_OF_STOCK -> outOfStock.incrementAndGet();
                            case STOCK_CONFLICT -> conflicts.incrementAndGet();
                            default -> errors.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) f.get();
            long elapsedMs = Math.max(1, (System.nanoTime() - begin) / 1_000_000);

            int finalStock = jdbc.queryForObject("select stock from product where id = ?", Integer.class, product.getId());
            int oversold = success.get() + finalStock - stock;
            Result result = new Result(strategy, stock, requests, success.get(), outOfStock.get(), conflicts.get(),
                    errors.get(), finalStock, oversold, oversold == 0, elapsedMs, requests * 1000L / elapsedMs);
            log.info("event=CONCURRENCY_TEST {}", result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
            cleanup(product.getId());
            ONE_AT_A_TIME.release();
        }
    }

    /** 실험 데이터가 주문 관리/정합성 리포트에 섞이지 않도록 지운다 */
    private void cleanup(Long productId) {
        // order_item → orders 외래키 때문에 품목을 먼저 지우고, 미리 모아둔 주문 ID로 주문을 지운다
        List<Long> orderIds = jdbc.queryForList(
                "select distinct order_id from order_item where product_id = ?", Long.class, productId);
        jdbc.update("delete from order_item where product_id = ?", productId);
        jdbc.batchUpdate("delete from orders where id = ? and user_id >= " + SYNTHETIC_USER_BASE,
                orderIds.stream().map(id -> new Object[]{id}).toList());
        jdbc.update("delete from product where id = ?", productId);
    }
}
