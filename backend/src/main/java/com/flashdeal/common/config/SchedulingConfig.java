package com.flashdeal.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 테스트에서는 스케줄러를 끄고(flashdeal.scheduler.enabled=false) 메서드를 직접 호출해 결정적으로 검증한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "flashdeal.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
