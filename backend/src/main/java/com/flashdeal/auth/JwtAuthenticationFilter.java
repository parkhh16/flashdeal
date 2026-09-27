package com.flashdeal.auth;

import com.flashdeal.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 서명 검증(무상태) + 사용자 상태 검증(캐시). 서명이 맞아도
 * - 탈퇴했거나 토큰 버전이 다르면(강제 로그아웃·정지 후 재발급 전 토큰) → 인증하지 않음 (401 SESSION_EXPIRED)
 * - 정지된 계정이면 → 인증하지 않음 (403 ACCOUNT_SUSPENDED 안내)
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR = "auth.error";
    private static final String BEARER = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserAuthCache userAuthCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            jwtProvider.parse(header.substring(BEARER.length())).ifPresent(claims -> {
                var snapshot = userAuthCache.get(claims.userId()).orElse(null);
                // 정지는 토큰 버전도 올리지만, 사용자가 이유를 알 수 있게 정지 여부를 먼저 본다
                if (snapshot != null && snapshot.status() == User.Status.SUSPENDED) {
                    request.setAttribute(AUTH_ERROR, ErrorCode.ACCOUNT_SUSPENDED);
                    return;
                }
                if (snapshot == null || snapshot.status() == User.Status.WITHDRAWN
                        || snapshot.tokenVersion() != claims.tokenVersion()) {
                    request.setAttribute(AUTH_ERROR, ErrorCode.SESSION_EXPIRED);
                    return;
                }
                var user = new AuthUser(snapshot.id(), snapshot.email(), snapshot.role());
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, authorities));
                MDC.put("userId", String.valueOf(user.id()));
            });
        }
        chain.doFilter(request, response);
    }
}
