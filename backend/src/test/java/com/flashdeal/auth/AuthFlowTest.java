package com.flashdeal.auth;

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
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AuthFlowTest {

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired TestFixtures fixtures;

    private Product product;

    @BeforeEach
    void setUp() {
        fixtures.clean();
        product = fixtures.product(100);
    }

    @Test
    @DisplayName("회원가입 → 아이디로 로그인 → 내 정보. 아이디 중복 확인 API")
    void signupAndLogin() {
        String id = newId();
        assertThat(get(null, "/api/auth/check-login-id?value=" + id).getBody().get("available").asBoolean()).isTrue();

        ResponseEntity<JsonNode> signup = signup(id, "password123", id + "@t.com");
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get(null, "/api/auth/check-login-id?value=" + id).getBody().get("available").asBoolean()).isFalse();

        String token = login(id, "password123").getBody().get("accessToken").asText();
        JsonNode me = get(token, "/api/me").getBody();
        assertThat(me.get("loginId").asText()).isEqualTo(id);
        assertThat(me.get("lastLoginAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("가입 검증: 아이디 형식, 비밀번호 정책(영문+숫자 8자), 중복 아이디 409")
    void signupValidation() {
        String id = newId();
        assertThat(signup("A!", "password123", id + "@t.com").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(signup(id, "onlyletters", id + "@t.com").getBody().get("code").asText()).isEqualTo("C001");
        signup(id, "password123", id + "@t.com");
        ResponseEntity<JsonNode> dup = signup(id, "password123", "other-" + id + "@t.com");
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("A003");
    }

    @Test
    @DisplayName("같은 아이디로 10명이 동시에 가입해도 1명만 성공한다 (중복 확인과 저장 사이 경쟁 → DB 유니크 제약이 최종 방어)")
    void concurrentSignup() throws Exception {
        String id = newId();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return signup(id, "password123", id + n + "@t.com");
            }));
        }
        start.countDown();
        int created = 0, conflict = 0;
        for (var f : futures) {
            HttpStatusCode s = f.get().getStatusCode();
            if (s == HttpStatus.CREATED) created++;
            if (s == HttpStatus.CONFLICT) conflict++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(9);
    }

    @Test
    @DisplayName("로그인 5회 실패 → 잠금. 잠긴 동안은 맞는 비밀번호도 거부, 관리자가 풀 수 있다")
    void lockout() {
        String id = newId();
        signup(id, "password123", id + "@t.com");
        for (int i = 0; i < 4; i++) {
            assertThat(login(id, "wrong-pass1").getBody().get("code").asText()).isEqualTo("A002");
        }
        assertThat(login(id, "wrong-pass1").getBody().get("code").asText()).isEqualTo("A006");
        assertThat(login(id, "password123").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        Long userId = userRepository.findByLoginId(id).orElseThrow().getId();
        assertThat(post(admin(), "/api/admin/users/" + userId + "/unlock", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(id, "password123").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("존재하지 않는 아이디와 틀린 비밀번호는 같은 코드로 응답 (계정 존재 여부 비노출)")
    void noUserEnumeration() {
        String id = newId();
        signup(id, "password123", id + "@t.com");
        assertThat(login("nobody_" + newId(), "password123").getBody().get("code").asText()).isEqualTo("A002");
        assertThat(login(id, "wrong-pass1").getBody().get("code").asText()).isEqualTo("A002");
    }

    @Test
    @DisplayName("탈퇴: 진행 중인 주문이 있으면 불가 → 결제 대기 주문이 없어지면 가능. 탈퇴 즉시 기존 토큰 401, 개인정보 익명화, 주문 기록은 보존, 같은 아이디로 재가입 가능")
    void withdraw() {
        String id = newId();
        String token = signup(id, "password123", id + "@t.com").getBody().get("accessToken").asText();
        Long userId = userRepository.findByLoginId(id).orElseThrow().getId();
        long orderId = order(token).getBody().get("id").asLong();

        ResponseEntity<JsonNode> blocked = delete(token, "/api/me", Map.of("password", "password123"));
        assertThat(blocked.getBody().get("code").asText()).isEqualTo("A007");

        post(admin(), "/api/admin/orders/" + orderId + "/cancel", null);
        assertThat(delete(token, "/api/me", Map.of("password", "wrong")).getBody().get("code").asText()).isEqualTo("A008");
        assertThat(delete(token, "/api/me", Map.of("password", "password123")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> after = get(token, "/api/me");
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(after.getBody().get("code").asText()).isEqualTo("A004");
        assertThat(login(id, "password123").getBody().get("code").asText()).isEqualTo("A002");

        User withdrawn = userRepository.findById(userId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(User.Status.WITHDRAWN);
        assertThat(withdrawn.getName()).isEqualTo("탈퇴회원");
        assertThat(withdrawn.getEmail()).doesNotContain(id);
        assertThat(fixtures.count("orders")).isEqualTo(1);

        assertThat(signup(id, "password123", id + "@t.com").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("관리자 정지 → 로그인 중이던 토큰도 즉시 403, 해제하면 다시 로그인 가능. 강제 로그아웃 → 기존 토큰 401")
    void adminControls() {
        String id = newId();
        String token = signup(id, "password123", id + "@t.com").getBody().get("accessToken").asText();
        Long userId = userRepository.findByLoginId(id).orElseThrow().getId();
        String admin = admin();

        patch(admin, "/api/admin/users/" + userId + "/status", Map.of("status", "SUSPENDED"));
        ResponseEntity<JsonNode> suspended = get(token, "/api/me");
        assertThat(suspended.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suspended.getBody().get("code").asText()).isEqualTo("A005");
        assertThat(login(id, "password123").getBody().get("code").asText()).isEqualTo("A005");

        patch(admin, "/api/admin/users/" + userId + "/status", Map.of("status", "ACTIVE"));
        String fresh = login(id, "password123").getBody().get("accessToken").asText();
        assertThat(get(fresh, "/api/me").getStatusCode()).isEqualTo(HttpStatus.OK);

        post(admin, "/api/admin/users/" + userId + "/logout", null);
        assertThat(get(fresh, "/api/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("관리자 회원 API: 일반 사용자는 403, 관리자 계정은 정지할 수 없다")
    void adminGuards() {
        String id = newId();
        String token = signup(id, "password123", id + "@t.com").getBody().get("accessToken").asText();
        assertThat(get(token, "/api/admin/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String admin = admin();
        Long adminId = userRepository.findByLoginId(adminLoginId).orElseThrow().getId();
        assertThat(patch(admin, "/api/admin/users/" + adminId + "/status", Map.of("status", "SUSPENDED"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode users = get(admin, "/api/admin/users?q=" + id).getBody();
        assertThat(users.get(0).get("loginId").asText()).isEqualTo(id);
    }

    // ── helpers ──
    private String adminLoginId;

    private String admin() {
        if (adminLoginId == null) {
            adminLoginId = "adm_" + newId().substring(0, 8);
            userRepository.save(new User(adminLoginId, adminLoginId + "@t.com", encoder.encode("admin"), "admin", User.Role.ADMIN));
        }
        return login(adminLoginId, "admin").getBody().get("accessToken").asText();
    }

    private static String newId() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private ResponseEntity<JsonNode> signup(String id, String pw, String email) {
        return rest.postForEntity("/api/auth/signup", Map.of("loginId", id, "password", pw, "name", "테스터", "email", email), JsonNode.class);
    }

    private ResponseEntity<JsonNode> login(String id, String pw) {
        return rest.postForEntity("/api/auth/login", Map.of("loginId", id, "password", pw), JsonNode.class);
    }

    private ResponseEntity<JsonNode> order(String token) {
        HttpHeaders h = headers(token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", List.of(Map.of("productId", product.getId(), "quantity", 1))), h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String token, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String token, String url, Object body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String token, String url, Object body) {
        return rest.exchange(url, HttpMethod.PATCH, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String token, String url, Object body) {
        return rest.exchange(url, HttpMethod.DELETE, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }
}
