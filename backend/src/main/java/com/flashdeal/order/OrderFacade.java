package com.flashdeal.order;

import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderDtos.CreateOrderRequest;
import com.flashdeal.order.OrderDtos.OrderResponse;
import com.flashdeal.product.stock.StockConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 낙관적 락 재시도는 트랜잭션 "바깥"에서 해야 한다.
 * 같은 트랜잭션 안에서 다시 시도하면 이미 읽어 둔 오래된 version을 그대로 보게 되기 때문이다.
 * 그래서 트랜잭션 경계(OrderService) 밖에 재시도를 담당하는 Facade를 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFacade {

    private static final int MAX_ATTEMPTS = 30;

    private final OrderService orderService;

    public OrderResponse create(Long userId, CreateOrderRequest request) {
        return create(userId, request, false);
    }

    public OrderResponse create(Long userId, CreateOrderRequest request, boolean allowInactive) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return orderService.create(userId, request, allowInactive);
            } catch (StockConflictException e) {
                backoff(attempt);
            }
        }
        log.warn("event=STOCK_RETRY_EXHAUSTED userId={}", userId);
        throw new BusinessException(ErrorCode.STOCK_CONFLICT);
    }

    private void backoff(int attempt) {
        // 지터를 섞어서 재시도가 한 시점에 다시 몰리지 않게 한다
        long millis = Math.min(50, 5L * attempt) + ThreadLocalRandom.current().nextLong(10);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.STOCK_CONFLICT);
        }
    }
}
