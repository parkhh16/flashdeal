package com.flashdeal.activity;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 활동 로그 비동기 배치 적재기.
 * 요청마다 INSERT를 동기로 하면 주문 API 지연이 로그 저장 시간만큼 늘고, 부하 시 DB 커넥션도 두 배로 쓴다.
 * → 요청 스레드는 메모리 큐에 넣기만 하고(논블로킹), 백그라운드 스레드가 300ms마다 모아서 batch INSERT 한다.
 * 큐가 가득 차면(DB 장애 등) 로그를 버리고 개수만 센다. 로그 때문에 주문이 실패하면 안 되기 때문이다.
 */
@Slf4j
@Component
public class ActivityRecorder {

    private static final int CAPACITY = 20_000;
    private static final int BATCH = 1_000;
    private static final String INSERT = "insert into activity_log " +
            "(user_id, event_type, method, target, status_code, latency_ms, request_id, detail, created_at) " +
            "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public record Entry(Long userId, ActivityEventType type, String method, String target, int status,
                        int latencyMs, String requestId, String detail, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "activity-log-writer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 저장은 항상 독립된 새 트랜잭션에서 한다. 관리자 조회(readOnly 트랜잭션) 도중에 flush()가 불리면
     * 그 트랜잭션의 커넥션을 같이 쓰게 되는데, MySQL 드라이버는 readOnly 커넥션의 INSERT를 실제로 거부한다.
     * (H2는 readOnly를 무시해서 몰랐다가 MySQL로 테스트하면서 발견)
     */
    private final TransactionTemplate requiresNew;

    public ActivityRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PostConstruct
    void start() {
        worker.scheduleWithFixedDelay(this::safeFlush, 300, 300, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
        safeFlush();
    }

    public void record(Entry entry) {
        if (!queue.offer(entry) && dropped.incrementAndGet() % 1000 == 1) {
            log.warn("event=ACTIVITY_LOG_DROPPED total={}", dropped.get());
        }
    }

    /** 큐에 쌓인 로그를 즉시 저장한다 (테스트, 관리자 조회 직전 호출) */
    public synchronized void flush() {
        List<Entry> batch = new ArrayList<>(BATCH);
        while (queue.drainTo(batch, BATCH) > 0) {
            requiresNew.executeWithoutResult(s -> insert(batch));
            batch.clear();
        }
    }

    private void insert(List<Entry> batch) {
        jdbc.batchUpdate(INSERT, batch, batch.size(), (ps, e) -> {
            if (e.userId() == null) ps.setNull(1, java.sql.Types.BIGINT);
            else ps.setLong(1, e.userId());
            ps.setString(2, e.type().name());
            ps.setString(3, e.method());
            ps.setString(4, e.target());
            ps.setInt(5, e.status());
            ps.setInt(6, e.latencyMs());
            ps.setString(7, e.requestId());
            ps.setString(8, e.detail());
            ps.setTimestamp(9, Timestamp.valueOf(e.createdAt()));
        });
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void safeFlush() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.warn("event=ACTIVITY_LOG_FLUSH_FAILED cause={}", e.toString());
        }
    }
}
