package com.flashdeal.activity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ActivityEventType {
    LOGIN("로그인"),
    SIGNUP("회원가입"),
    SEARCH("검색"),
    PRODUCT_VIEW("상품 조회"),
    ORDER_CREATE("주문"),
    IDEMPOTENT_REPLAY("멱등성 재생"),
    PAYMENT("결제"),
    ORDER_VIEW("주문 조회"),
    ADMIN_ACTION("관리자 작업"),
    ACCOUNT("계정 변경"),
    ERROR("서버 에러");

    private final String label;
}
