package com.needsshop.common.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.needsshop.auth.AuthService;
import com.needsshop.auth.User;
import com.needsshop.auth.UserRepository;
import com.needsshop.product.Product;
import com.needsshop.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 한도. 고정 윈도(1분)라 테스트 도중 분이 바뀌면 카운터가 초기화되므로, 시계를 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "needsshop.rate-limit.enabled=true",
        "needsshop.rate-limit.login-per-minute=3",
        "needsshop.rate-limit.order-per-minute=2"})
@ActiveProfiles("test")
@Import({TestFixtures.class, RateLimitTest.FixedClock.class})
class RateLimitTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.now(), ZoneId.systemDefault());
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthService auth;
    @Autowired TestFixtures fixtures;

    private ResponseEntity<JsonNode> post(String path, Object body, String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String newUserToken() {
        String loginId = "rl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        users.save(new User(loginId, loginId + "@t.com", encoder.encode("password123"), "tester", User.Role.USER));
        return auth.login(loginId, "password123").accessToken();
    }

    @Test
    @DisplayName("로그인은 계정이 아니라 IP 기준: 서로 다른 계정으로 돌려 가며 시도해도 분당 한도를 넘으면 429")
    void loginLimitedPerIp() {
        for (int i = 0; i < 3; i++) {
            Map<String, String> body = Map.of("loginId", "nobody" + i, "password", "wrong-password1");
            assertThat(post("/api/auth/login", body, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<JsonNode> limited = post("/api/auth/login", Map.of("loginId", "nobody9", "password", "x"), null, null);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asText()).isEqualTo("C006");
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("주문은 사용자 기준: 한 사용자가 한도를 넘으면 429, 다른 사용자는 영향이 없다")
    void orderLimitedPerUser() {
        Product product = fixtures.product(100);
        Map<String, Object> order = Map.of("items", java.util.List.of(Map.of("productId", product.getId(), "quantity", 1)));
        String alice = newUserToken();
        String bob = newUserToken();

        assertThat(post("/api/orders", order, alice, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/orders", order, alice, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/orders", order, alice, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(post("/api/orders", order, bob, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.stockOf(product.getId())).as("거절된 주문은 재고를 건드리지 않는다").isEqualTo(97);
    }
}
