package com.trova.backend.queue;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueWaitMetricsTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 30, 12, 0, 0);

    private final ProcessingJobRepository repository = mock(ProcessingJobRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private QueueWaitMetrics metricsAt(LocalDateTime now) {
        return new QueueWaitMetrics(repository, registry, () -> now);
    }

    private void jobCreatedAt(Long id, LocalDateTime createdAt) {
        ProcessingJob job = mock(ProcessingJob.class);
        when(job.getCreatedAt()).thenReturn(createdAt);
        when(repository.findById(id)).thenReturn(Optional.of(job));
    }

    private Timer timer(String path) {
        return registry.find("trova.queue.wait").tag("path", path).timer();
    }

    @Test
    void 작업이_만들어진_뒤_시작까지_기다린_시간을_경로별로_남긴다() {
        jobCreatedAt(1L, CREATED);
        jobCreatedAt(2L, CREATED);

        long kafka = metricsAt(CREATED.plusNanos(132_000_000)).record(1L, QueuePath.KAFKA);
        long async = metricsAt(CREATED.plusSeconds(3)).record(2L, QueuePath.ASYNC);

        assertThat(kafka).isEqualTo(132);
        assertThat(async).isEqualTo(3_000);
        assertThat(timer("kafka").count()).isEqualTo(1);
        assertThat(timer("kafka").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(132);
        assertThat(timer("async").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(3_000);
    }

    @Test
    void 시계가_어긋나_음수면_0으로_남긴다() {
        jobCreatedAt(1L, CREATED);

        assertThat(metricsAt(CREATED.minusSeconds(2)).record(1L, QueuePath.KAFKA)).isZero();
        assertThat(timer("kafka").totalTime(TimeUnit.MILLISECONDS)).isZero();
    }

    @Test
    void 작업이_없으면_남기지_않는다() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThat(metricsAt(CREATED).record(9L, QueuePath.KAFKA)).isEqualTo(-1);
        assertThat(timer("kafka")).isNull();
    }
}
