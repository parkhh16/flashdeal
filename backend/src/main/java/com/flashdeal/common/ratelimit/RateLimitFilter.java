package com.flashdeal.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.auth.AuthUser;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.common.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 남용을 막는 요청 한도. 로그인은 계정 잠금(5회)과 별개로 IP당 시도 수를 제한해서, 여러 계정을 돌려 가며 찍는 공격을 막는다.
 * 주문은 사용자당 제한해서 매크로가 재고를 쓸어 가지 못하게 한다.
 *
 * Spring Security 필터 체인 뒤에서 실행되므로 인증된 사용자를 알 수 있다.
 * 클라이언트 IP는 프록시(Caddy)가 넣은 X-Forwarded-For를 서버가 복원한 값이다 (prod: forward-headers-strategy).
 * 로컬·테스트에서는 꺼져 있다 (부하 테스트가 한 IP에서 수천 건을 보낸다). 운영 프로필에서 켠다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "flashdeal.rate-limit.enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;
    private final int loginPerMinute;
    private final int signupPerMinute;
    private final int orderPerMinute;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper objectMapper,
                           @Value("${flashdeal.rate-limit.login-per-minute:20}") int loginPerMinute,
                           @Value("${flashdeal.rate-limit.signup-per-minute:10}") int signupPerMinute,
                           @Value("${flashdeal.rate-limit.order-per-minute:30}") int orderPerMinute) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
        this.loginPerMinute = loginPerMinute;
        this.signupPerMinute = signupPerMinute;
        this.orderPerMinute = orderPerMinute;
    }

    private record Rule(String name, String subject, int limit) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule != null) {
            RateLimiter.Decision decision = limiter.tryAcquire(rule.name() + ":" + rule.subject(), rule.limit(), WINDOW);
            if (!decision.allowed()) {
                log.warn("event=RATE_LIMITED rule={} subject={} retryAfter={}s", rule.name(), rule.subject(), decision.retryAfterSeconds());
                response.setStatus(ErrorCode.TOO_MANY_REQUESTS.getStatus().value());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.TOO_MANY_REQUESTS));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Rule ruleFor(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case "/api/auth/login" -> new Rule("login", "ip:" + request.getRemoteAddr(), loginPerMinute);
            case "/api/auth/signup" -> new Rule("signup", "ip:" + request.getRemoteAddr(), signupPerMinute);
            case "/api/orders" -> new Rule("order", subject(request), orderPerMinute);
            default -> null;
        };
    }

    private static String subject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser user ? "user:" + user.id() : "ip:" + request.getRemoteAddr();
    }
}
