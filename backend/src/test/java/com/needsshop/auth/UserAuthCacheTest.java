package com.needsshop.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 상태 캐시 무효화. 정지·탈퇴·강제 로그아웃이 캐시 때문에 늦게 반영되는 두 가지 경로를 막는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAuthCacheTest {

    @Autowired UserAuthCache userAuthCache;
    @Autowired UserRepository userRepository;
    @Autowired AuthService authService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(new User(UUID.randomUUID() + "@t.com", "x", "tester", User.Role.USER)).getId();
    }

    private int tokenVersionIn(UserAuthCache cache) {
        return cache.get(userId).orElseThrow().tokenVersion();
    }

    @Test
    @DisplayName("커밋 전에 들어온 요청이 옛 상태를 캐시해도, 커밋 후에 지우므로 다음 요청은 새 상태를 본다")
    void evictsAfterCommit() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            authService.logoutEverywhere(userId);   // 토큰 버전 0 → 1, 캐시 무효화 (아직 커밋 전)
            // 같은 순간 다른 요청(다른 스레드, 다른 커넥션)이 캐시를 채운다. 커밋 전이라 옛 버전 0을 읽는다
            CompletableFuture.runAsync(() -> userAuthCache.get(userId)).join();
        });

        assertThat(tokenVersionIn(userAuthCache))
                .as("커밋 전에 지웠다면 옛 버전(0)이 30초 동안 남아 기존 토큰이 계속 통과한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("서버가 두 대면, 다른 서버에 알리지 않을 때 그 서버는 옛 상태를 계속 쓰고, 알리면 바로 지운다")
    void propagatesToOtherServers() {
        UserAuthCache[] servers = new UserAuthCache[2];
        // Redis Pub/Sub 대신 메모리로 연결한 두 서버. 실제 Redis 연결은 서버 환경(CI server-env)에서 확인한다
        servers[0] = new UserAuthCache(userRepository, id -> servers[1].evictLocal(id));
        servers[1] = new UserAuthCache(userRepository, id -> servers[0].evictLocal(id));
        UserAuthCache isolated = new UserAuthCache(userRepository, id -> { });
        UserAuthCache isolatedPeer = new UserAuthCache(userRepository, id -> { });
        for (UserAuthCache cache : new UserAuthCache[]{servers[0], servers[1], isolated, isolatedPeer}) {
            assertThat(tokenVersionIn(cache)).isZero();   // 네 서버 모두 버전 0을 캐시
        }

        jdbc.update("update users set token_version = token_version + 1 where id = ?", userId);
        servers[0].evict(userId);
        isolated.evict(userId);

        assertThat(tokenVersionIn(servers[1])).as("알림을 받은 다른 서버").isEqualTo(1);
        assertThat(tokenVersionIn(isolatedPeer)).as("알림이 없으면 다른 서버는 TTL(30초)까지 옛 상태").isZero();
    }
}
