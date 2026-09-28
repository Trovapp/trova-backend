package com.trova.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 재시작 때 진행 중인 비동기 작업을 끊지 않고 끝날 때까지 기다려야 한다(#43). */
class AsyncConfigShutdownTest {

    @Test
    void 영상_처리_실행기는_종료할_때_진행_중인_작업이_끝날_때까지_기다린다() throws Exception {
        assertWaitsForRunningTask(new AsyncConfig().pipelineTaskExecutor());
    }

    @Test
    void 일정_재구성_실행기는_종료할_때_진행_중인_작업이_끝날_때까지_기다린다() throws Exception {
        assertWaitsForRunningTask(new AsyncConfig().replanTaskExecutor());
    }

    private static void assertWaitsForRunningTask(Executor bean) throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(500);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        executor.shutdown();

        assertThat(finished).isTrue();
    }
}
