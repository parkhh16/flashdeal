package com.needsshop.auth;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

/**
 * JWT는 서버에 상태가 없어서, 탈퇴·정지·강제 로그아웃을 해도 토큰 만료(2시간) 전까지는 계속 쓸 수 있다.
 * → 토큰에 token_version을 넣고 요청마다 DB의 현재 버전과 비교한다.
 *
 * 매 요청 DB를 조회하면 JWT를 쓰는 의미(무상태, 빠름)가 사라지므로, 사용자 상태를 서버 메모리 캐시에 둔다.
 * 상태를 바꾸면 두 가지를 지킨다.
 * 1. 커밋한 뒤에 지운다. 커밋 전에 지우면, 그 사이에 들어온 요청이 커밋 전의 옛 상태를 다시 읽어 TTL(30초) 동안 캐시에 남긴다.
 * 2. 다른 서버에도 지우라고 알린다(Redis Pub/Sub). 알리지 않으면 다른 서버는 TTL만큼 옛 상태로 인증을 통과시킨다.
 *    서버가 한 대라 Redis를 끈 환경에서는 알릴 곳이 없으므로 아무것도 하지 않는다.
 */
@Component
public class UserAuthCache {

    public record Snapshot(Long id, String email, User.Role role, User.Status status, int tokenVersion) {
    }

    private final LoadingCache<Long, Optional<Snapshot>> cache;
    private final UserCacheInvalidationPublisher publisher;

    public UserAuthCache(UserRepository userRepository, UserCacheInvalidationPublisher publisher) {
        this.publisher = publisher;
        this.cache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build(id -> userRepository.findById(id)
                        .map(u -> new Snapshot(u.getId(), u.getEmail(), u.getRole(), u.getStatus(), u.getTokenVersion())));
    }

    public Optional<Snapshot> get(Long userId) {
        return cache.get(userId);
    }

    /** 사용자 상태를 바꾼 쪽이 부른다. 트랜잭션 안이면 커밋 후에, 밖이면 바로 모든 서버에서 지운다 */
    public void evict(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictEverywhere(userId);
                }
            });
        } else {
            evictEverywhere(userId);
        }
    }

    /** 다른 서버가 보낸 무효화 메시지를 받았을 때. 다시 전파하지 않는다 */
    public void evictLocal(Long userId) {
        cache.invalidate(userId);
    }

    private void evictEverywhere(Long userId) {
        cache.invalidate(userId);
        publisher.publish(userId);
    }
}
