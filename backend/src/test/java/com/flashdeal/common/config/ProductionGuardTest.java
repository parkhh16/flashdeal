package com.flashdeal.common.config;

import com.flashdeal.FlashDealApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 설정 안전장치. 데모 기본값을 그대로 둔 채 배포하면 서버가 뜨지 않아야 한다.
 */
class ProductionGuardTest {

    private static final String STRONG_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static MockEnvironment safe() {
        return new MockEnvironment()
                .withProperty("flashdeal.jwt.secret", STRONG_SECRET)
                .withProperty("flashdeal.seed.enabled", "true")
                .withProperty("flashdeal.seed.admin-password", "long-random-password")
                .withProperty("flashdeal.seed.demo-users", "false")
                .withProperty("spring.datasource.url", "jdbc:mysql://db:3306/flashdeal")
                .withProperty("flashdeal.dev-tools.enabled", "false");
    }

    @Test
    @DisplayName("안전한 운영 설정이면 문제가 없다")
    void safeSettings() {
        assertThat(ProductionGuard.violations(safe())).isEmpty();
    }

    @Test
    @DisplayName("데모 기본값은 하나하나 모두 문제로 잡는다")
    void demoDefaults() {
        MockEnvironment env = safe()
                .withProperty("flashdeal.jwt.secret", "")
                .withProperty("flashdeal.seed.admin-password", "admin")
                .withProperty("flashdeal.seed.demo-users", "true")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:flashdeal")
                .withProperty("flashdeal.dev-tools.enabled", "true");

        assertThat(ProductionGuard.violations(env)).hasSize(5)
                .anyMatch(p -> p.contains("JWT_SECRET"))
                .anyMatch(p -> p.contains("ADMIN_PASSWORD"))
                .anyMatch(p -> p.contains("데모 회원"))
                .anyMatch(p -> p.contains("H2"))
                .anyMatch(p -> p.contains("개발 도구"));
    }

    @Test
    @DisplayName("JWT 키는 Base64로 디코딩한 길이가 32바이트 이상이어야 한다 (HS256)")
    void shortSecret() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThat(ProductionGuard.violations(safe().withProperty("flashdeal.jwt.secret", shortKey)))
                .singleElement().asString().contains("JWT_SECRET");
        assertThat(ProductionGuard.violations(safe().withProperty("flashdeal.jwt.secret", "%%%not-base64%%%")))
                .singleElement().asString().contains("JWT_SECRET");
    }

    @Test
    @DisplayName("prod 프로필로 환경변수 없이 띄우면 다른 빈이 만들어지기 전에 기동이 중단된다")
    void prodProfileRefusesToStart() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(FlashDealApplication.class)
                .profiles("prod")
                .properties("server.port=0", "spring.datasource.url=jdbc:h2:mem:guard-test");

        assertThatThrownBy(() -> app.run().close())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod 프로필 기동 중단")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    /** 문서에는 ADMIN_PASSWORD로 관리자 비밀번호를 바꾼다고 되어 있었지만, 실제로는 어디에도 연결되지 않아 admin/admin이 그대로였다 */
    @Nested
    @SpringBootTest(properties = "ADMIN_PASSWORD=from-environment-variable")
    @ActiveProfiles("test")
    @DisplayName("ADMIN_PASSWORD 환경변수가 시드 관리자 비밀번호로 연결된다")
    class AdminPasswordWiring {

        @Value("${flashdeal.seed.admin-password}") String adminPassword;

        @Test
        void wired() {
            assertThat(adminPassword).isEqualTo("from-environment-variable");
        }
    }
}
