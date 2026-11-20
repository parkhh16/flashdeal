package com.needsshop.common.cluster;

import com.needsshop.order.OrderDtos.CreateOrderRequest;
import com.needsshop.order.OrderExpiryScheduler;
import com.needsshop.order.OrderFacade;
import com.needsshop.order.OrderRepository;
import com.needsshop.order.OrderStatus;
import com.needsshop.product.Product;
import com.needsshop.support.TestFixtures;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 여러 대일 때 스케줄러를 한 서버에서만 실행하는지. "서버 두 대"는 같은 DB를 보는 락 제공자 두 개로 흉내 낸다.
 * 주문 TTL을 음수로 줘서 주문을 만들자마자 결제 기한이 지난 상태를 만든다.
 */
@SpringBootTest(properties = "needsshop.order.reservation-ttl=-1s")
@ActiveProfiles("test")
@Import(TestFixtures.class)
class ClusterLockTest {

    @Autowired DataSource dataSource;
    @Autowired LockProvider lockProvider;
    @Autowired OrderExpiryScheduler expiryScheduler;
    @Autowired OrderFacade orderFacade;
    @Autowired OrderRepository orderRepository;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        jdbc.update("delete from shedlock");
    }

    private LockingTaskExecutor server(String name) {
        return new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().withLockedByValue(name).build()));
    }

    @Test
    @DisplayName("두 서버가 같은 작업을 동시에 시작하면 한 서버만 실행하고, 끝나면 다른 서버가 다음 주기에 실행할 수 있다")
    void onlyOneServerRuns() throws Exception {
        LockingTaskExecutor serverA = server("server-A");
        LockingTaskExecutor serverB = server("server-B");
        String name = "test-job-" + UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> serverA.executeWithLock((Runnable) () -> {
            runs.incrementAndGet();
            aStarted.countDown();
            await(release);
        }, config(name)));
        assertThat(aStarted.await(10, TimeUnit.SECONDS)).isTrue();

        serverB.executeWithLock((Runnable) runs::incrementAndGet, config(name));
        assertThat(runs.get()).as("A가 실행 중이면 B는 건너뛴다").isEqualTo(1);

        release.countDown();
        a.get(10, TimeUnit.SECONDS);
        serverB.executeWithLock((Runnable) runs::incrementAndGet, config(name));
        assertThat(runs.get()).as("A가 끝나면 B가 실행한다").isEqualTo(2);
    }

    @Test
    @DisplayName("다른 서버가 주문 만료 락을 쥐고 있으면 이 서버의 만료 스케줄러는 건너뛰고, 락이 풀리면 처리한다")
    void expirySchedulerRespectsOtherServersLock() {
        Product product = fixtures.product(10);
        Long orderId = orderFacade.create(1L,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(product.getId(), 2)))).id();

        SimpleLock otherServer = lockProvider.lock(config("order-expiry")).orElseThrow();
        expiryScheduler.scheduledExpiry();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(8);

        otherServer.unlock();
        expiryScheduler.scheduledExpiry();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
    }

    private static LockConfiguration config(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
