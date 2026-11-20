package com.needsshop.common.cluster;

import com.needsshop.auth.UserAuthCache;
import com.needsshop.auth.UserCacheInvalidationPublisher;
import com.needsshop.common.ratelimit.RateLimiter;
import com.needsshop.common.ratelimit.RedisRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * 서버 여러 대 (needsshop.redis.enabled=true, 서버 환경 compose).
 * - 사용자 캐시 무효화: 상태를 바꾼 서버가 채널에 userId를 보내면, 모든 서버(자기 자신 포함)가 자기 메모리 캐시에서 지운다.
 *   캐시는 여전히 서버 메모리라 인증할 때 네트워크를 타지 않고, 무효화만 Redis로 전파한다.
 * - 요청 한도: 모든 서버가 같은 카운터를 센다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "needsshop.redis.enabled", havingValue = "true")
public class RedisClusterConfig {

    static final String USER_INVALIDATION_CHANNEL = "needsshop:user-auth-invalidation";

    @Bean
    public UserCacheInvalidationPublisher userCacheInvalidationPublisher(StringRedisTemplate redis) {
        return userId -> {
            try {
                redis.convertAndSend(USER_INVALIDATION_CHANNEL, String.valueOf(userId));
            } catch (RuntimeException e) {
                // 전파에 실패해도 상태 변경은 이미 커밋됐다. 다른 서버는 캐시 TTL(30초) 안에 따라온다
                log.warn("event=USER_INVALIDATION_PUBLISH_FAILED userId={} cause={}", userId, e.toString());
            }
        };
    }

    @Bean
    public RedisMessageListenerContainer userInvalidationListener(RedisConnectionFactory connectionFactory,
                                                                  UserAuthCache userAuthCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            Long userId = Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
            userAuthCache.evictLocal(userId);
            log.debug("event=USER_INVALIDATION_RECEIVED userId={}", userId);
        }, new ChannelTopic(USER_INVALIDATION_CHANNEL));
        return container;
    }

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redis, Clock clock) {
        return new RedisRateLimiter(redis, clock);
    }
}
