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
        executor.initialize();
        return executor;
    }

    // PlaceExtractionService.process() 자체가 pipelineTaskExecutor(스레드 2~4개) 위에서
    // 돈다 — 장소별 지오코딩 팬아웃을 같은 풀에 던지면, 그 풀의 스레드가 자기 자신이
    // 제출한 하위 작업이 끝나길 join()으로 기다리며 풀 안에서 서로 경합하게 된다(풀이
    // 작을수록 큐잉만 늘고 병렬화 효과가 줄어듦). 별도 풀로 분리해 이 자기잠식을 막는다.
    @Bean("geocodingTaskExecutor")
    public Executor geocodingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("geocoding-");
        executor.initialize();
        return executor;
    }
}
