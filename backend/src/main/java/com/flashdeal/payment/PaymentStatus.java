package com.flashdeal.payment;

public enum PaymentStatus {
    /** PG 호출 직전에 먼저 기록한다. 이후 서버가 죽어도 이 기록을 보고 대사할 수 있다 */
    REQUESTED,
    APPROVED,
    FAILED,
    /** 타임아웃 등으로 결과를 모른다. 대사 스케줄러가 PG에 조회해서 확정한다 */
    UNKNOWN,
    /** 관리자 주문 취소로 PG 환불 완료 */
    CANCELLED
}
