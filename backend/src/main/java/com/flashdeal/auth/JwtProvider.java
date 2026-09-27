package com.flashdeal.auth;

import com.flashdeal.common.config.FlashDealProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtProvider {

    public record TokenClaims(Long userId, int tokenVersion) {
    }

    private final SecretKey key;
    private final Duration expiration;

    public JwtProvider(FlashDealProperties properties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
        this.expiration = properties.jwt().expiration();
    }

    /** 역할은 토큰에 넣지 않는다. 요청 시점의 DB 상태(캐시)를 기준으로 해야 권한 변경이 즉시 반영된다 */
    public String issue(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("ver", user.getTokenVersion())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration.toMillis()))
                .signWith(key)
                .compact();
    }

    public Optional<TokenClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Integer ver = claims.get("ver", Integer.class);
            return Optional.of(new TokenClaims(Long.valueOf(claims.getSubject()), ver == null ? -1 : ver));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
