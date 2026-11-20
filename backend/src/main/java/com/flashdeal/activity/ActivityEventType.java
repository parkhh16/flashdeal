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
    ERROR("서버 에러"),
    /** 외부 AI(Gemini)가 응답하지 않음. 우리 서버 장애가 아니라서 서버 에러율에 넣지 않고 따로 센다 */
    AI_FAILURE("외부 AI 실패");

    private final String label;
}
