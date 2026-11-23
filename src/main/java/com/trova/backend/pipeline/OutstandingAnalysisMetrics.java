package com.trova.backend.pipeline;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.repository.ProcessingJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.metrics.otlp.enabled", havingValue = "true")
public class OutstandingAnalysisMetrics implements MeterBinder {
    private final ProcessingJobRepository jobs;
    private volatile double outstanding = Double.NaN;

    public OutstandingAnalysisMetrics(ProcessingJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("trova.analysis.outstanding", this, metrics -> metrics.outstanding)
                .description("Video analyses waiting or running across all queues")
                .register(registry);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void sample() {
        try {
            outstanding = jobs.countByAnalysisFinishedAtIsNullAndStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        } catch (RuntimeException failedSample) {
            outstanding = Double.NaN;
            throw failedSample;
        }
    }
}
