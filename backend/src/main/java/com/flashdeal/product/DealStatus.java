package com.flashdeal.product;

public enum DealStatus {
    /** 특가가 아닌 일반 상품 */
    NONE,
    /** 오픈 전: 구매 불가 */
    UPCOMING,
    ONGOING,
    SOLD_OUT,
    /** 기간 종료: 구매 불가 */
    ENDED
}
