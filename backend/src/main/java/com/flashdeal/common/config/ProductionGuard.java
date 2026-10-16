package com.flashdeal.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * prod 프로필에서는 데모용 기본값으로 서버가 뜨지 못하게 막는다 (fail fast).
 * 환경변수를 하나 빠뜨린 채 배포하면 저장소에 공개된 JWT 키로 누구나 토큰을 위조할 수 있고, admin/admin으로 관리자에 들어올 수 있다.
 * 그런 서버가 "잘 떠 있는" 것보다 아예 안 뜨는 편이 안전하다.
 *
 * BeanFactoryPostProcessor라서 다른 빈(JwtProvider, DataInitializer 등)이 만들어지기 전에 검사한다.
 */
@Component
@Profile("prod")
public class ProductionGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> problems = violations(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("prod 프로필 기동 중단 - 운영 설정이 안전하지 않습니다:\n - "
                    + String.join("\n - ", problems));
        }
    }

    static List<String> violations(Environment env) {
        List<String> problems = new ArrayList<>();

        String secret = env.getProperty("flashdeal.jwt.secret", "");
        if (decodedLength(secret) < 32) {
            problems.add("JWT_SECRET이 없거나 256bit(32바이트)보다 짧습니다. Base64로 인코딩한 32바이트 이상의 임의 값을 넣으세요");
        }

        boolean seed = env.getProperty("flashdeal.seed.enabled", Boolean.class, true);
        String adminPassword = env.getProperty("flashdeal.seed.admin-password", "");
        if (seed && (adminPassword.length() < 12 || adminPassword.equalsIgnoreCase("admin"))) {
            problems.add("ADMIN_PASSWORD가 없거나 12자보다 짧습니다 (처음 시드되는 관리자 계정의 비밀번호)");
        }
        if (seed && env.getProperty("flashdeal.seed.demo-users", Boolean.class, false)) {
            problems.add("데모 회원(user1, user2)은 비밀번호가 공개되어 있어 운영에서 만들 수 없습니다 (flashdeal.seed.demo-users=false)");
        }

        if (env.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:")) {
            problems.add("운영에서 메모리 DB(H2)는 쓸 수 없습니다. 재시작하면 주문과 결제가 사라집니다");
        }

        if (env.getProperty("flashdeal.dev-tools.enabled", Boolean.class, false)) {
            problems.add("개발 도구(재고 전략 전환 API)는 운영에서 켤 수 없습니다. NAIVE 전략은 초과 판매가 납니다");
        }
        return problems;
    }

    private static int decodedLength(String base64) {
        if (base64 == null || base64.isBlank()) return 0;
        try {
            return Base64.getDecoder().decode(base64.strip()).length;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
