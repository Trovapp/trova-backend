package com.trova.backend.pipeline;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.repository.ProcessingJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutstandingAnalysisMetricsTest {
    @Test
    void 첫_측정과_실패는_미수집이고_성공한_0건은_표시한다() {
        ProcessingJobRepository jobs = mock(ProcessingJobRepository.class);
        when(jobs.countByAnalysisFinishedAtIsNullAndStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING)))
                .thenReturn(2L).thenThrow(new IllegalStateException("조회 실패")).thenReturn(0L);
        OutstandingAnalysisMetrics metrics = new OutstandingAnalysisMetrics(jobs);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        Gauge gauge = registry.get("trova.analysis.outstanding").gauge();
        assertThat(gauge.value()).isNaN();
        metrics.sample();
        assertThat(gauge.value()).isEqualTo(2);
        assertThatThrownBy(metrics::sample).isInstanceOf(IllegalStateException.class);
        assertThat(gauge.value()).isNaN();
        metrics.sample();
        assertThat(gauge.value()).isZero();
    }
}
