package com.flashdeal.order;

import com.flashdeal.product.Product;
import com.flashdeal.support.TestFixtures;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 주문 목록 조회의 N+1 문제와 인덱스 효과를 측정한다.
 */
class OrderQueryPerformanceTest {

    static final long USER = 7L;
    static final int ORDERS = 20;

    /** 공통: 주문 20건(각 2개 품목)을 만들고 목록 1페이지(20건)를 조회할 때 나가는 쿼리 수를 센다 */
    abstract static class QueryCountBase {
        @Autowired OrderFacade orderFacade;
        @Autowired OrderService orderService;
        @Autowired TestFixtures fixtures;
        @Autowired EntityManagerFactory emf;

        @BeforeEach
        void setUp() {
            fixtures.clean();
            Product a = fixtures.product("A", 1000, 1000);
            Product b = fixtures.product("B", 2000, 1000);
            for (int i = 0; i < ORDERS; i++) {
                orderFacade.create(USER, new OrderDtos.CreateOrderRequest(List.of(
                        new OrderDtos.CreateOrderRequest.Line(a.getId(), 1),
                        new OrderDtos.CreateOrderRequest.Line(b.getId(), 1))));
            }
        }

        long countQueries() {
            Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
            stats.clear();
            OrderDtos.PageResponse<OrderDtos.OrderResponse> page = orderService.getMyOrders(USER, 0, ORDERS);
            assertThat(page.content()).hasSize(ORDERS);
            assertThat(page.content()).allMatch(o -> o.items().size() == 2);
            return stats.getPrepareStatementCount();
        }
    }

    @Nested
    @SpringBootTest(properties = "spring.jpa.properties.hibernate.default_batch_fetch_size=1")
    @ActiveProfiles("test")
    @Import(TestFixtures.class)
    class Before extends QueryCountBase {
        @Test
        @DisplayName("[Before] 배치 페치 없음: 주문 목록 1 + count 1 + 주문별 items N = 22 쿼리")
        void nPlusOne() {
            long queries = countQueries();
            System.out.println("[N+1 BEFORE] queries = " + queries);
            assertThat(queries).isEqualTo(2 + ORDERS);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestFixtures.class)
    class After extends QueryCountBase {
        @Test
        @DisplayName("[After] default_batch_fetch_size=100: items를 IN 쿼리 1번으로 묶어 3 쿼리")
        void batchFetch() {
            long queries = countQueries();
            System.out.println("[N+1 AFTER] queries = " + queries);
            assertThat(queries).isEqualTo(3);
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestFixtures.class)
    class IndexPlan {
        @Autowired JdbcTemplate jdbc;
        @Autowired TestFixtures fixtures;

        /** 내 주문 목록 쿼리. 주문 5,000건(사용자 100명) 중 사용자 7의 최근 10건 */
        static final String QUERY = "select * from orders where user_id = 7 order by id desc limit 10";

        @Test
        @DisplayName("(user_id, id) 복합 인덱스 유무에 따른 실행 계획 비교 (H2 / MySQL 공통)")
        void explain() throws Exception {
            fixtures.clean();
            // 행이 몇 개 없으면 옵티마이저가 인덱스가 있어도 풀 스캔을 고를 수 있어서, 현실적인 양을 넣고 비교한다
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            jdbc.batchUpdate("insert into orders (order_no, user_id, status, total_amount, expires_at, version, created_at, updated_at) " +
                            "values (?, ?, 'PAID', 10000, ?, 0, ?, ?)",
                    java.util.stream.IntStream.range(0, 5_000).mapToObj(i -> new Object[]{
                            "IDX-" + i + "-" + java.util.UUID.randomUUID().toString().substring(0, 8), (long) (i % 100 + 1), now, now, now
                    }).toList());
            boolean mysql = jdbc.getDataSource().getConnection().getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
            if (mysql) jdbc.execute("analyze table orders");

            String with = plan(mysql);
            jdbc.execute(mysql ? "drop index idx_orders_user_id_id on orders" : "drop index idx_orders_user_id_id");
            String without = plan(mysql);
            jdbc.execute("create index idx_orders_user_id_id on orders(user_id, id)");
            fixtures.clean();

            System.out.println("[EXPLAIN WITHOUT INDEX]\n" + without);
            System.out.println("[EXPLAIN WITH INDEX]\n" + with);
            assertThat(with).contains("idx_orders_user_id_id");
            assertThat(without).doesNotContain("idx_orders_user_id_id");
            if (!mysql) {
                // H2: 인덱스가 없으면 PK를 역순으로 훑으면서 user_id를 필터링한다 (다른 사용자 주문까지 읽음)
                assertThat(without).contains("PRIMARY_KEY");
            }
        }

        /** MySQL은 EXPLAIN ANALYZE로 "실제로 읽은 행 수"까지 본다 */
        private String plan(boolean mysql) {
            return mysql
                    ? String.join("\n", jdbc.queryForList("explain analyze " + QUERY, String.class))
                    : jdbc.queryForObject("explain " + QUERY, String.class);
        }
    }
}
