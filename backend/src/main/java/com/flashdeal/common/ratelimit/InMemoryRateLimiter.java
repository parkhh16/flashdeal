package com.flashdeal.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** 서버 한 대용. 윈도마다 키를 새로 만들고, 지난 윈도의 카운터는 시간이 지나면 캐시에서 빠진다 */
public class InMemoryRateLimiter implements RateLimiter {

    private final Clock clock;
    private final Cache<String, AtomicLong> counters = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        long count = counters.get(key + ":" + now / windowMillis, k -> new AtomicLong()).incrementAndGet();
        return Decision.of(count, limit, now, windowMillis);
    }
}
