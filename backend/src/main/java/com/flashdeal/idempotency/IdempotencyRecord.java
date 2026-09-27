package com.flashdeal.idempotency;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_user_key", columnNames = {"user_id", "idempotency_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    public enum Status {IN_PROGRESS, COMPLETED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    /** 같은 키로 "다른" 요청을 보내는 실수를 잡기 위해 요청 본문 해시를 저장한다 */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status;

    private Integer responseStatus;

    @Column(length = 8000)
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public IdempotencyRecord(Long userId, String idempotencyKey, String requestHash) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = LocalDateTime.now();
    }

    public void complete(int responseStatus, String responseBody) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }
}
