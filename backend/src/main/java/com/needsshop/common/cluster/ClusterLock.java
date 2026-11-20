package com.needsshop.common.cluster;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 서버가 여러 대일 때 같은 작업을 한 번에 한 서버에서만 실행한다 (ShedLock).
 * 락을 얻지 못하면 기다리지 않고 건너뛴다. 주기 작업은 다음 주기에 다시 시도하면 된다.
 *
 * 스케줄러 메서드에 @SchedulerLock(AOP)을 붙이지 않고 진입점에서 명시적으로 감싼다.
 * AOP 방식은 직접 호출(테스트)까지 락을 걸고, 락을 못 얻으면 int 반환값 자리에 null을 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class ClusterLock {

    private final LockingTaskExecutor executor;

    /**
     * @param lockAtMostFor 이 서버가 작업 도중 죽어도 이 시간이 지나면 다른 서버가 락을 가져간다. 작업의 최장 시간보다 길게 잡는다
     * @return 이 서버에서 실행했으면 true, 다른 서버가 실행 중이라 건너뛰었으면 false
     */
    public boolean runExclusively(String name, Duration lockAtMostFor, Runnable task) {
        LockConfiguration config = new LockConfiguration(Instant.now(), name, lockAtMostFor, Duration.ZERO);
        try {
            return executor.executeWithLock((LockingTaskExecutor.TaskWithResult<Void>) () -> {
                task.run();
                return null;
            }, config).wasExecuted();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
