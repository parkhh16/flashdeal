package com.flashdeal.common.cluster;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 서버 사이의 락은 이미 모든 서버가 공유하는 DB의 shedlock 테이블(V6)로 잡는다.
 * Redis가 없어도 동작하고, 락 때문에 인프라를 하나 더 늘리지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class ClusterLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()   // 서버마다 시계가 조금씩 달라도 DB 시각 하나로 만료를 판단한다
                .build());
    }

    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
