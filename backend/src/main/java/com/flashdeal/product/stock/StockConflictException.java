package com.flashdeal.product.stock;

/**
 * 낙관적 락 충돌. 트랜잭션 전체를 다시 시도해야 하므로 OrderFacade에서 잡아서 재시도한다.
 */
public class StockConflictException extends RuntimeException {

    public StockConflictException(Long productId) {
        super("stock version conflict. productId=" + productId);
    }
}
