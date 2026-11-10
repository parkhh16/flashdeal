package com.flashdeal.common.ratelimit;

import java.time.Duration;

/**
 * 고정 윈도 카운터. 같은 키로 window 동안 limit번까지 허용한다.
 * 서버가 한 대면 서버 메모리(InMemoryRateLimiter), 여러 대면 모든 서버가 같은 카운터를 보도록 Redis(RedisRateLimiter).
 * 서버마다 따로 세면 서버 수만큼 한도가 늘어난다 (2대면 분당 20회 제한이 사실상 40회).
 */
public interface RateLimiter {

    Decision tryAcquire(String key, int limit, Duration window);

    /** @param retryAfterSeconds 거절했을 때 다음 윈도까지 남은 시간 (Retry-After 헤더) */
    record Decision(boolean allowed, long retryAfterSeconds) {

        static Decision of(long count, int limit, long nowMillis, long windowMillis) {
            if (count <= limit) return new Decision(true, 0);
            long windowEnd = (nowMillis / windowMillis + 1) * windowMillis;
            return new Decision(false, Math.max(1, (windowEnd - nowMillis + 999) / 1000));
        }
    }
}
