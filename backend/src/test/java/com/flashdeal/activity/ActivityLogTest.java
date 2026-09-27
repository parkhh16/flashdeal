package com.flashdeal.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestFixtures.class)
class ActivityLogTest {

    @Autowired TestRestTemplate rest;
    @Autowired TestFixtures fixtures;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;

    private Product product;
    private Session user;
    private Session other;
    private Session admin;

    record Session(Long id, String token) {
    }

    @BeforeEach
    void setUp() {
        fixtures.clean();
        product = fixtures.product(100);
        user = create(User.Role.USER);
        other = create(User.Role.USER);
        admin = create(User.Role.ADMIN);
    }

    @Test
    @DisplayName("사용자 행동(로그인/검색/상품 조회/주문)이 서버에 저장되고, 관리자는 사용자별로 조회한다")
    void recordsAndFiltersByUser() {
        get(user, "/api/products/search?q=" + "키보드");
        get(user, "/api/products/" + product.getId());
        order(user, UUID.randomUUID().toString());
        get(other, "/api/products/" + product.getId());

        JsonNode logs = get(admin, "/api/admin/activity?userId=" + user.id()).getBody();

        List<String> types = new ArrayList<>();
        logs.get("content").forEach(n -> types.add(n.get("eventType").asText()));
        assertThat(types).containsExactly("ORDER_CREATE", "PRODUCT_VIEW", "SEARCH", "LOGIN");
        JsonNode orderLog = logs.get("content").get(0);
        assertThat(orderLog.get("statusCode").asInt()).isEqualTo(201);
        assertThat(orderLog.get("detail").get("orderNo").asText()).startsWith("ORD-");
        assertThat(orderLog.get("requestId").asText()).isNotBlank();
        assertThat(logs.get("content").get(3).get("detail").get("loginId").asText()).contains("***");
    }

    @Test
    @DisplayName("멱등성 재생과 비즈니스 에러도 구분되어 남는다")
    void replayAndErrors() {
        String key = UUID.randomUUID().toString();
        order(user, key);
        order(user, key);
        get(user, "/api/products/999999");

        JsonNode logs = get(admin, "/api/admin/activity?userId=" + user.id() + "&status=CLIENT_ERROR").getBody();
        assertThat(logs.get("content").get(0).get("detail").get("errorCode").asText()).isEqualTo("P001");

        JsonNode summary = get(admin, "/api/admin/activity/summary?userId=" + user.id()).getBody();
        assertThat(summary.get("byType").get("IDEMPOTENT_REPLAY").asInt()).isEqualTo(1);
        assertThat(summary.get("orders").asInt()).isEqualTo(1);
        assertThat(summary.get("clientErrors").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("내 활동: 최근 검색어와 최근 본 상품. 개발자용 정보(상태코드 등)는 포함하지 않는다")
    void myActivity() {
        get(user, "/api/products/search?q=" + "마우스");
        get(user, "/api/products/" + product.getId());

        JsonNode me = get(user, "/api/users/" + user.id() + "/activity").getBody();

        assertThat(me.get("recentSearches").get(0).asText()).isEqualTo("마우스");
        assertThat(me.get("recentlyViewed").get(0).get("id").asLong()).isEqualTo(product.getId());
        assertThat(me.toString()).doesNotContain("statusCode", "latency", "parsedBy");
    }

    @Test
    @DisplayName("권한: 남의 활동 조회는 403 (IDOR), 일반 사용자의 관리자 로그 API는 403, 관리자도 사용자용 API로 남의 활동 조회 불가")
    void authorization() {
        assertThat(get(user, "/api/users/" + other.id() + "/activity").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(user, "/api/admin/activity").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(user, "/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(admin, "/api/users/" + user.id() + "/activity").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(user, "/api/users/" + user.id() + "/activity").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Session create(User.Role role) {
        String email = "a" + UUID.randomUUID().toString().substring(0, 8) + "@t.com";
        User u = userRepository.save(new User(email, encoder.encode("password123!"), "tester", role));
        JsonNode res = rest.postForEntity("/api/auth/login", Map.of("loginId", email, "password", "password123!"), JsonNode.class).getBody();
        return new Session(u.getId(), res.get("accessToken").asText());
    }

    private ResponseEntity<JsonNode> get(Session s, String url) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token());
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }

    private void order(Session s, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token());
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(Map.of("productId", product.getId(), "quantity", 1))), h), JsonNode.class);
    }
}
