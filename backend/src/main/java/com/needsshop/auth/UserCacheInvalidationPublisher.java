package com.needsshop.auth;

/**
 * 사용자 상태 캐시를 지웠다는 사실을 다른 서버에 알린다.
 * 서버가 여러 대면 Redis Pub/Sub(RedisClusterConfig), 한 대면 알릴 곳이 없어 아무것도 하지 않는다(LocalOnlyClusterConfig).
 */
public interface UserCacheInvalidationPublisher {

    void publish(Long userId);
}
