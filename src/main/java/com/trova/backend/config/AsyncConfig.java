package com.trova.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean("pipelineTaskExecutor")
    public Executor pipelineTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pipeline-");
        applyGracefulShutdown(executor);
        executor.initialize();
        return executor;
    }

    @Bean("replanTaskExecutor")
    public Executor replanTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("replan-");
        applyGracefulShutdown(executor);
        executor.initialize();
        return executor;
    }

    /**
     * 영상 하나의 장소별 카카오 검색을 동시에 보내는 풀(#7). process()가 도는 pipelineTaskExecutor에 같이 넣으면
     * 그 풀의 스레드가 자기가 넣은 하위 작업을 기다리며 서로 자리를 막으므로 따로 둔다.
     * 큐가 다 차기 전에는 core 수까지만 스레드가 뜨므로 core=max로 동시 요청 수(8)를 고정하고,
     * 쉬는 동안은 스레드를 내려 메모리가 작은 운영 서버(956MB)에 상주시키지 않는다.
     */
    @Bean("geocodingTaskExecutor")
    public Executor geocodingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("geocoding-");
        applyGracefulShutdown(executor);
        executor.initialize();
        return executor;
    }

    /**
     * 재시작·배포 때 진행 중이거나 대기열에 있는 작업을 최대 60초 기다린 뒤 내려간다(#43). 기다리지 않으면
     * 작업이 DB에 PENDING/PROCESSING으로 남는다 — 60초 안에 못 끝낸 작업은 OrphanedJobRecoveryService가 정리한다.
     * 영상 처리 실측 중앙값 26초·재구성 최대 38초 기준.
     */
    private static void applyGracefulShutdown(ThreadPoolTaskExecutor executor) {
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
    }
}
