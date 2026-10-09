package com.flashdeal.common.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashdeal.auth.AuthService;
import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import org.junit.jupiter.api.DisplayName;
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
 * 없는 경로와 지원하지 않는 메서드는 서버 오류(500)가 아니라 404/405로 응답해야 한다.
 * 전역 Exception 핸들러가 이 예외들까지 받으면 500으로 응답하고 에러 로그와 traceId를 낭비한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotFoundHandlingTest {

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthService auth;

    private HttpEntity<Void> authenticated() {
        // 시드 계정에 기대지 않고 직접 만든다 (MySQL 테스트는 컨텍스트끼리 스키마를 공유한다)
        String loginId = "nf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        users.save(new User(loginId, loginId + "@t.com", encoder.encode("password123"), "tester", User.Role.USER));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(auth.login(loginId, "password123").accessToken());
        return new HttpEntity<>(headers);
    }

    @Test
    @DisplayName("없는 경로는 404(C004)")
    void unknownPath() {
        ResponseEntity<JsonNode> res = rest.exchange("/api/no-such-path", HttpMethod.GET, authenticated(), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().get("code").asText()).isEqualTo("C004");
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405(C005)")
    void methodNotAllowed() {
        // 로그인은 POST만 받는다
        ResponseEntity<JsonNode> res = rest.exchange("/api/auth/login", HttpMethod.GET, authenticated(), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody().get("code").asText()).isEqualTo("C005");
    }
}
