package com.flashdeal.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 시간 의존 로직(TTL 만료 등)을 테스트에서 제어할 수 있도록 Clock을 빈으로 주입한다 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
