package com.flashdeal.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashdeal.product.Product;
import com.flashdeal.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFixtures.class)
class OrderApiTest {

    @Autowired TestRestTemplate rest;
    @Autowired TestFixtures fixtures;

    private Product product;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        product = fixtures.product(100);
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 두 번 주문하면 주문은 1건이고, 두 번째는 저장된 응답을 재생한다")
    void replay() {
        String token = signup();
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = order(token, key, product.getId(), 1);
        ResponseEntity<JsonNode> second = order(token, key, product.getId(), 1);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(fixtures.count("orders")).isEqualTo(1);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(99);
    }

    @Test
    @DisplayName("같은 키로 다른 요청을 보내면 422")
    void keyReusedWithDifferentBody() {
        String token = signup();
        String key = UUID.randomUUID().toString();
        order(token, key, product.getId(), 1);

        ResponseEntity<JsonNode> res = order(token, key, product.getId(), 2);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().get("code").asText()).isEqualTo("I002");
    }

    @Test
    @DisplayName("따닥: 같은 키로 20개 요청이 동시에 와도 주문은 정확히 1건 생성된다")
    void doubleClick() throws Exception {
        String token = signup();
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return order(token, key, product.getId(), 1);
            }));
        }
        start.countDown();
        List<HttpStatusCode> statuses = new ArrayList<>();
        for (var f : futures) statuses.add(f.get().getStatusCode());
        pool.shutdown();

        assertThat(fixtures.count("orders")).isEqualTo(1);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(99);
        // 먼저 도착한 1건만 201로 처리되고, 나머지는 201 재생 또는 409(처리 중)
        assertThat(statuses).allMatch(s -> s == HttpStatus.CREATED || s == HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Idempotency-Key가 없으면 400")
    void missingKey() {
        String token = signup();
        HttpHeaders headers = auth(token);
        ResponseEntity<JsonNode> res = rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(body(product.getId(), 1), headers), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("I001");
    }

    @Test
    @DisplayName("인가: 다른 사람의 주문은 404, 토큰 없으면 401, 일반 사용자의 관리자 API는 403")
    void authorization() {
        String owner = signup();
        String other = signup();
        long orderId = order(owner, UUID.randomUUID().toString(), product.getId(), 1).getBody().get("id").asLong();

        assertThat(rest.exchange("/api/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(auth(owner)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(auth(other)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/orders/" + orderId, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/admin/chaos", HttpMethod.GET, new HttpEntity<>(auth(owner)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("에러 응답에는 traceId가 포함되어 로그와 연결된다")
    void traceId() {
        ResponseEntity<JsonNode> res = rest.getForEntity("/api/products/999999", JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("traceId").asText()).isEqualTo(res.getHeaders().getFirst("X-Trace-Id"));
    }

    private ResponseEntity<JsonNode> order(String token, String key, Long productId, int qty) {
        HttpHeaders headers = auth(token);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(body(productId, qty), headers), JsonNode.class);
    }

    private Map<String, Object> body(Long productId, int qty) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", qty)));
    }

    private String signup() {
        String email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/auth/signup",
                Map.of("loginId", email.substring(0, email.indexOf("@")), "email", email, "password", "password123!", "name", "tester"), JsonNode.class);
        return res.getBody().get("accessToken").asText();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
