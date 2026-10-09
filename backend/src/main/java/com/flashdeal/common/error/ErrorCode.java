package com.flashdeal.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 분기할 수 있도록 HTTP 상태와 별개로 도메인 에러 코드를 내려준다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C002", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C003", "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C005", "지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "일시적인 오류가 발생했습니다."),

    // auth
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "A001", "이미 가입된 이메일입니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A002", "아이디 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "A003", "이미 사용 중인 아이디입니다."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "A004", "로그인이 만료되었습니다. 다시 로그인해주세요."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "A005", "이용이 정지된 계정입니다. 고객센터에 문의해주세요."),
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "A006", "로그인에 여러 번 실패해 잠시 잠겼습니다. 5분 후 다시 시도해주세요."),
    WITHDRAW_BLOCKED(HttpStatus.CONFLICT, "A007", "진행 중인 주문(결제 대기·결제 확인 중)이 있어 탈퇴할 수 없습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "A008", "비밀번호가 일치하지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "A009", "회원을 찾을 수 없습니다."),
    INVALID_USER_OPERATION(HttpStatus.CONFLICT, "A010", "이 회원에게는 수행할 수 없는 작업입니다."),

    // product / stock
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "상품을 찾을 수 없습니다."),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "P002", "재고가 부족합니다."),
    STOCK_CONFLICT(HttpStatus.CONFLICT, "P003", "주문이 몰려 처리하지 못했습니다. 다시 시도해주세요."),

    // order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "O001", "주문을 찾을 수 없습니다."),
    INVALID_ORDER_STATUS(HttpStatus.CONFLICT, "O002", "현재 주문 상태에서 처리할 수 없는 요청입니다."),
    ORDER_EXPIRED(HttpStatus.CONFLICT, "O003", "결제 기한이 지난 주문입니다."),
    DEAL_NOT_OPEN(HttpStatus.CONFLICT, "O004", "아직 특가가 시작되지 않았습니다."),
    DEAL_ENDED(HttpStatus.CONFLICT, "O005", "특가 판매가 종료되었습니다."),
    PURCHASE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "O006", "1인 구매 한도를 초과했습니다."),

    // payment
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "PM001", "결제가 거절되었습니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.BAD_GATEWAY, "PM002", "PG 결제 취소에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // idempotency
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "I001", "Idempotency-Key 헤더가 필요합니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "I002", "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다."),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "I003", "동일한 요청이 처리 중입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
