package com.flashdeal.auth;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * JWT는 서버에 상태가 없어서, 탈퇴·정지·강제 로그아웃을 해도 토큰 만료(2시간) 전까지는 계속 쓸 수 있다.
 * → 토큰에 token_version을 넣고 요청마다 DB의 현재 버전과 비교한다.
 *
 * 매 요청 DB를 조회하면 JWT를 쓰는 의미(무상태, 빠름)가 사라지므로, 사용자 상태를 로컬 캐시에 둔다.
 * 상태를 바꾸는 쪽(탈퇴/정지/강제 로그아웃)이 즉시 evict 하므로 같은 서버에서는 바로 반영되고,
 * 서버가 여러 대면 다른 서버는 최대 TTL(30초)만큼 늦게 반영된다. (더 줄이려면 Redis pub/sub로 evict 전파)
 */
@Component
public class UserAuthCache {

    public record Snapshot(Long id, String email, User.Role role, User.Status status, int tokenVersion) {
    }

    private final LoadingCache<Long, Optional<Snapshot>> cache;

    public UserAuthCache(UserRepository userRepository) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build(id -> userRepository.findById(id)
                        .map(u -> new Snapshot(u.getId(), u.getEmail(), u.getRole(), u.getStatus(), u.getTokenVersion())));
    }

    public Optional<Snapshot> get(Long userId) {
        return cache.get(userId);
    }

    public void evict(Long userId) {
        cache.invalidate(userId);
    }
}
