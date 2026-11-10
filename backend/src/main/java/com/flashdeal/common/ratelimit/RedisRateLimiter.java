package com.flashdeal.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 서버 여러 대용. 모든 서버가 Redis의 같은 카운터를 올린다.
 * INCR과 만료 설정을 스크립트 하나로 묶어서, 두 명령 사이에 서버가 죽어 만료 없는 키가 남는 일을 막는다.
 * Redis가 응답하지 않으면 허용한다(fail open). 레이트 리밋 때문에 로그인·주문 자체가 막히면 안 되기 때문이다.
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        String redisKey = "ratelimit:" + key + ":" + now / windowMillis;
        try {
            Long count = redis.execute(INCREMENT_WITH_TTL, List.of(redisKey), String.valueOf(windowMillis));
            return Decision.of(count == null ? 0 : count, limit, now, windowMillis);
        } catch (RuntimeException e) {
            log.warn("event=RATE_LIMIT_REDIS_UNAVAILABLE key={} cause={}", key, e.toString());
            return new Decision(true, 0);
        }
    }
}
