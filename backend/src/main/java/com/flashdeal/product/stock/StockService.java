package com.flashdeal.product.stock;

import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 재고 차감 전략을 런타임에 바꿀 수 있게 해서, 같은 부하에서 전략별 결과를 비교한다.
 * 모든 메서드는 호출한 쪽(주문 생성)의 트랜잭션에 참여한다. 주문 저장이 실패하면 차감도 같이 롤백된다.
 */
@Slf4j
@Service
public class StockService {

    private final ProductRepository productRepository;
    private final AtomicReference<StockStrategyType> strategy;

    public StockService(ProductRepository productRepository, FlashDealProperties properties) {
        this.productRepository = productRepository;
        this.strategy = new AtomicReference<>(properties.stock().strategy());
    }

    public StockStrategyType currentStrategy() {
        return strategy.get();
    }

    public void changeStrategy(StockStrategyType type) {
        log.info("event=STOCK_STRATEGY_CHANGED from={} to={}", strategy.get(), type);
        strategy.set(type);
    }

    /**
     * @return 주문 스냅샷(상품명, 가격)을 만들 때 쓸 상품
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Product decrease(Long productId, int quantity) {
        return switch (strategy.get()) {
            case NAIVE -> decreaseNaive(productId, quantity);
            case PESSIMISTIC -> decreasePessimistic(productId, quantity);
            case OPTIMISTIC -> decreaseOptimistic(productId, quantity);
            case ATOMIC_UPDATE -> decreaseAtomic(productId, quantity);
        };
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(Long productId, int quantity) {
        productRepository.increaseStock(productId, quantity);
    }

    private Product decreaseNaive(Long productId, int quantity) {
        Product product = findProduct(productId);
        product.decreaseStock(quantity); // 커밋 시점에 변경 감지로 UPDATE되며, 그 사이에 다른 트랜잭션의 차감을 덮어쓴다
        return product;
    }

    private Product decreasePessimistic(Long productId, int quantity) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
        return product;
    }

    private Product decreaseOptimistic(Long productId, int quantity) {
        Product product = findProduct(productId);
        if (product.getStock() < quantity) {
            throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        }
        if (productRepository.decreaseIfVersionMatches(productId, quantity, product.getVersion()) == 0) {
            throw new StockConflictException(productId);
        }
        return product;
    }

    private Product decreaseAtomic(Long productId, int quantity) {
        if (productRepository.decreaseIfEnough(productId, quantity) == 0) {
            // 0건 갱신이면 상품이 없거나 재고가 부족한 경우다
            findProduct(productId);
            throw new BusinessException(ErrorCode.OUT_OF_STOCK);
        }
        return findProduct(productId);
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
