package com.flashdeal.common.cluster;

import com.flashdeal.auth.UserCacheInvalidationPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 서버 한 대 (Redis 꺼짐): 알릴 다른 서버가 없다. 로컬과 테스트의 기본값 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flashdeal.redis.enabled", havingValue = "false", matchIfMissing = true)
public class LocalOnlyClusterConfig {

    @Bean
    public UserCacheInvalidationPublisher userCacheInvalidationPublisher() {
        return userId -> {
        };
    }
}
