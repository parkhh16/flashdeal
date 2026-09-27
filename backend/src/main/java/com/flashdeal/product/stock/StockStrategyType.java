package com.flashdeal.product.stock;

public enum StockStrategyType {
    /** 락 없음. 읽고 → 검사하고 → 쓰기. Lost Update로 오버셀링이 발생한다 (문제 재현용) */
    NAIVE,
    /** SELECT FOR UPDATE. 정합성은 확실하지만 한 행에 요청이 몰리면 모두 줄을 서게 된다 */
    PESSIMISTIC,
    /** version 비교 후 UPDATE, 충돌하면 재시도. 충돌이 드문 곳에 적합하고 핫스팟에서는 재시도 폭증 */
    OPTIMISTIC,
    /** UPDATE ... WHERE stock >= qty. DB 행 락 하나로 검사와 차감을 원자적으로 처리한다 (기본값) */
    ATOMIC_UPDATE
}
