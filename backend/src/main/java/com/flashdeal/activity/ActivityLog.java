package com.flashdeal.activity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** 조회 전용 매핑. 쓰기는 ActivityRecorder가 JDBC 배치로 한다 */
@Getter
@Entity
@Table(name = "activity_log", indexes = {
        @Index(name = "idx_activity_user_id_id", columnList = "user_id, id"),
        @Index(name = "idx_activity_created_at", columnList = "created_at"),
        @Index(name = "idx_activity_type_created_at", columnList = "event_type, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_type", nullable = false, length = 30)
    private ActivityEventType eventType;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 200)
    private String target;

    @Column(nullable = false)
    private int statusCode;

    @Column(nullable = false)
    private int latencyMs;

    @Column(length = 64)
    private String requestId;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
