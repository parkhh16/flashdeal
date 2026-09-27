package com.flashdeal.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.auth.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 의미 있는 API 요청을 활동 로그로 남긴다. Spring Security 필터 체인 안쪽에서 실행되므로 인증된 사용자를 알 수 있다.
 * 폴링이나 목록 조회처럼 양만 많고 의미가 적은 요청, 로그 조회 API 자신은 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ActivityLogFilter extends OncePerRequestFilter {

    private static final Pattern PRODUCT_DETAIL = Pattern.compile("^/api/products/(\\d+)$");
    private static final Pattern ORDER_DETAIL = Pattern.compile("^/api/orders/\\d+$");
    private static final Pattern PAYMENT = Pattern.compile("^/api/orders/(\\d+)/payment$");

    private final ActivityRecorder recorder;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            int latency = (int) ((System.nanoTime() - start) / 1_000_000);
            ActivityEventType type = classify(request, response);
            if (type != null) {
                recorder.record(new ActivityRecorder.Entry(userId(request), type, request.getMethod(),
                        request.getRequestURI(), response.getStatus(), latency, MDC.get("traceId"),
                        detail(request), LocalDateTime.now()));
            }
        }
    }

    private ActivityEventType classify(HttpServletRequest request, HttpServletResponse response) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        ActivityEventType type = routeType(method, uri, response);
        if (type != null && response.getStatus() >= 500) {
            return ActivityEventType.ERROR;
        }
        return type;
    }

    private ActivityEventType routeType(String method, String uri, HttpServletResponse response) {
        boolean get = "GET".equals(method);
        if (uri.equals("/api/auth/login")) return ActivityEventType.LOGIN;
        if (uri.equals("/api/auth/signup")) return ActivityEventType.SIGNUP;
        if (get && uri.equals("/api/products/search")) return ActivityEventType.SEARCH;
        if (get && PRODUCT_DETAIL.matcher(uri).matches()) return ActivityEventType.PRODUCT_VIEW;
        if ("POST".equals(method) && uri.equals("/api/orders")) {
            return "true".equals(response.getHeader("Idempotent-Replayed"))
                    ? ActivityEventType.IDEMPOTENT_REPLAY : ActivityEventType.ORDER_CREATE;
        }
        if ("POST".equals(method) && PAYMENT.matcher(uri).matches()) {
            return "true".equals(response.getHeader("Idempotent-Replayed"))
                    ? ActivityEventType.IDEMPOTENT_REPLAY : ActivityEventType.PAYMENT;
        }
        if (get && ORDER_DETAIL.matcher(uri).matches()) return ActivityEventType.ORDER_VIEW;
        if (!get && uri.startsWith("/api/admin/")) return ActivityEventType.ADMIN_ACTION;
        if (!get && uri.startsWith("/api/me")) return ActivityEventType.ACCOUNT; // 탈퇴, 모든 기기 로그아웃
        return null; // 목록 조회, 폴링, 로그 조회 API 등은 남기지 않는다
    }

    private Long userId(HttpServletRequest request) {
        Object explicit = request.getAttribute(ActivityContext.USER_ID);
        if (explicit instanceof Long id) return id;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser user ? user.id() : null;
    }

    private String detail(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) request.getAttribute(ActivityContext.DETAIL);
        var m = PRODUCT_DETAIL.matcher(request.getRequestURI());
        if (m.matches()) {
            detail = new java.util.LinkedHashMap<>(detail == null ? Map.of() : detail);
            detail.put("productId", Long.valueOf(m.group(1)));
        }
        if (detail == null || detail.isEmpty()) return null;
        try {
            String json = objectMapper.writeValueAsString(detail);
            return json.length() > 1000 ? json.substring(0, 1000) : json;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
