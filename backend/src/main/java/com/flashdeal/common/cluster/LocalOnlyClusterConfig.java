package com.flashdeal.common.cluster;

import com.flashdeal.auth.UserCacheInvalidationPublisher;
import com.flashdeal.common.ratelimit.InMemoryRateLimiter;
import com.flashdeal.common.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 서버 한 대 (Redis 꺼짐): 알릴 다른 서버가 없고, 요청 한도는 서버 메모리로 센다. 로컬과 테스트의 기본값 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flashdeal.redis.enabled", havingValue = "false", matchIfMissing = true)
public class LocalOnlyClusterConfig {

    @Bean
    public UserCacheInvalidationPublisher userCacheInvalidationPublisher() {
        return userId -> {
        };
    }

    @Bean
    public RateLimiter rateLimiter(Clock clock) {
        return new InMemoryRateLimiter(clock);
    }
}
