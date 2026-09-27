package com.flashdeal.payment;

import com.flashdeal.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_order_id", columnList = "order_id"),
        // 대사 스케줄러: WHERE status IN ('REQUESTED','UNKNOWN') AND created_at < ?
        @Index(name = "idx_payment_status_created_at", columnList = "status, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** PG에 보내는 멱등 키. 이 키가 같으면 PG는 한 번만 결제한다 */
    @Column(nullable = false, unique = true, length = 60)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 40)
    private String pgTransactionId;

    @Column(length = 100)
    private String failReason;

    @Version
    private long version;

    public Payment(Long orderId, String paymentKey, long amount) {
        this.orderId = orderId;
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
    }

    public void approve(String pgTransactionId) {
        this.status = PaymentStatus.APPROVED;
        this.pgTransactionId = pgTransactionId;
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
    }

    public void markUnknown(String reason) {
        this.status = PaymentStatus.UNKNOWN;
        this.failReason = reason;
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELLED;
    }

    public boolean isFinished() {
        return status == PaymentStatus.APPROVED || status == PaymentStatus.FAILED;
    }
}
