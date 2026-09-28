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
     * 재시작·배포 때 진행 중이거나 대기열에 있는 작업을 최대 60초 기다린 뒤 내려간다(#43). 기다리지 않으면
     * 작업이 DB에 PENDING/PROCESSING으로 남는다 — 60초 안에 못 끝낸 작업은 OrphanedJobRecoveryService가 정리한다.
     * 영상 처리 실측 중앙값 26초·재구성 최대 38초 기준.
     */
    private static void applyGracefulShutdown(ThreadPoolTaskExecutor executor) {
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
    }
}
