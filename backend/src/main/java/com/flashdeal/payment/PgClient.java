package com.flashdeal.payment;

public interface PgClient {

    PgResult approve(String paymentKey, String orderNo, long amount);

    PgResult inquire(String paymentKey);

    /** 승인 취소(환불). PG는 paymentKey 기준으로 멱등하게 처리한다 */
    PgResult cancel(String paymentKey);

    record PgResult(Outcome outcome, String transactionId, String reason) {

        public static PgResult unknown(String reason) {
            return new PgResult(Outcome.UNKNOWN, null, reason);
        }
    }

    enum Outcome {
        APPROVED,
        DECLINED,
        /** PG에 결제 기록이 없다 = 요청이 PG에 도달하지 못했다 */
        NOT_FOUND,
        CANCELLED,
        /** 타임아웃, 5xx 등으로 결과를 알 수 없다. 절대 실패로 단정하면 안 된다 */
        UNKNOWN
    }
}
