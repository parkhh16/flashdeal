package com.flashdeal.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashdeal.auth.AuthService;
import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.product.stock.StockService;
import com.flashdeal.product.stock.StockStrategyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 전략 전환 API는 개발 도구 플래그를 켠 경우에만 존재한다.
 * 기본값(꺼짐)에서는 관리자도 이 API로 초과 판매가 나는 전략(NAIVE)으로 바꿀 수 없어야 한다.
 *
 * 시드 계정(admin)에 기대지 않고 테스트가 직접 관리자를 만든다. 시드는 회원 테이블이 비어 있을 때만 들어가는데,
 * MySQL로 테스트하면 모든 컨텍스트가 같은 스키마를 공유해서 다른 테스트의 회원이 남아 있을 수 있기 때문이다.
 */
class DevToolsFlagTest {

    private static final String PASSWORD = "password123";

    private static String newToken(UserRepository users, PasswordEncoder encoder, AuthService auth, User.Role role) {
        String loginId = "dt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        users.save(new User(loginId, loginId + "@t.com", encoder.encode(PASSWORD), "tester", role));
        return auth.login(loginId, PASSWORD).accessToken();
    }

    private static HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @DisplayName("기본값: 꺼짐")
    class Disabled {

        @Autowired TestRestTemplate rest;
        @Autowired UserRepository users;
        @Autowired PasswordEncoder encoder;
        @Autowired AuthService auth;
        @Autowired StockService stockService;

        @Test
        @DisplayName("관리자도 재고 전략 API를 쓸 수 없고, 전략은 바뀌지 않는다")
        void notExposed() {
            String token = newToken(users, encoder, auth, User.Role.ADMIN);

            ResponseEntity<JsonNode> get = rest.exchange("/api/admin/stock-strategy", HttpMethod.GET, bearer(token), JsonNode.class);
            assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get.getBody().get("code").asText()).as("서버 오류(500)가 아니라 없는 경로로 응답").isEqualTo("C004");
            // 없는 경로에 대한 PUT은 정적 리소스 핸들러가 GET만 받기 때문에 405로 나온다. 어느 쪽이든 전략이 바뀌면 안 된다
            assertThat(rest.exchange("/api/admin/stock-strategy/NAIVE", HttpMethod.PUT, bearer(token), JsonNode.class)
                    .getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(stockService.currentStrategy()).isEqualTo(StockStrategyType.ATOMIC_UPDATE);
        }

        @Test
        @DisplayName("장애 주입, 수동 배치, 동시성 테스트 같은 실험용 API는 더 이상 없다")
        void labEndpointsAreGone() {
            String token = newToken(users, encoder, auth, User.Role.ADMIN);

            for (String path : new String[]{"/api/admin/chaos", "/api/admin/reconcile", "/api/admin/expire", "/api/admin/tools/concurrency-test"}) {
                assertThat(rest.exchange(path, HttpMethod.GET, bearer(token), JsonNode.class).getStatusCode())
                        .as(path).isEqualTo(HttpStatus.NOT_FOUND);
            }
        }

        @Test
        @DisplayName("정합성 리포트는 운영 기능이라 관리자에게 열려 있다")
        void consistencyReportRemains() {
            String token = newToken(users, encoder, auth, User.Role.ADMIN);

            assertThat(rest.exchange("/api/admin/consistency-report", HttpMethod.GET, bearer(token), JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "flashdeal.dev-tools.enabled=true")
    @ActiveProfiles("test")
    @DisplayName("flashdeal.dev-tools.enabled=true")
    class Enabled {

        @Autowired TestRestTemplate rest;
        @Autowired UserRepository users;
        @Autowired PasswordEncoder encoder;
        @Autowired AuthService auth;
        @Autowired StockService stockService;

        @AfterEach
        void restore() {
            stockService.changeStrategy(StockStrategyType.ATOMIC_UPDATE);
        }

        @Test
        @DisplayName("관리자는 재고 전략을 조회하고 바꿀 수 있고, 일반 사용자는 403")
        void adminCanSwitch() {
            String admin = newToken(users, encoder, auth, User.Role.ADMIN);
            String user = newToken(users, encoder, auth, User.Role.USER);

            assertThat(rest.exchange("/api/admin/stock-strategy/PESSIMISTIC", HttpMethod.PUT, bearer(admin), JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(rest.exchange("/api/admin/stock-strategy", HttpMethod.GET, bearer(admin), JsonNode.class)
                    .getBody().get("strategy").asText()).isEqualTo("PESSIMISTIC");

            assertThat(rest.exchange("/api/admin/stock-strategy", HttpMethod.GET, bearer(user), JsonNode.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
