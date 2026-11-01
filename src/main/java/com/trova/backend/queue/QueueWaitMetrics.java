package com.trova.backend.queue;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 공유로 작업이 만들어진 뒤 분석이 시작되기까지의 대기(#249) — trova_queue_wait_seconds{path}.
 * 경로별 대기는 DB 시작 상태 확인도 포함한다. 분석 시간은 입력/모델/분기 영향이 있으므로 별도로 비교한다.
 * createdAt은 서버 기본 시간대의 LocalDateTime.now()로 저장되므로 같은 방식으로 비교한다.
 */
@Component
public class QueueWaitMetrics {

    // 구간 비율만 보면 돼서 버킷을 몇 개로 묶는다 — 운영 지표 수를 크게 늘리지 않게.
    private static final Duration[] BUCKETS = {
            Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)};

    private final ProcessingJobRepository processingJobRepository;
    private final MeterRegistry registry;
    private final Supplier<LocalDateTime> now;

    @Autowired
    public QueueWaitMetrics(ProcessingJobRepository processingJobRepository, MeterRegistry registry) {
        this(processingJobRepository, registry, LocalDateTime::now);
    }

    QueueWaitMetrics(ProcessingJobRepository processingJobRepository, MeterRegistry registry, Supplier<LocalDateTime> now) {
        this.processingJobRepository = processingJobRepository;
        this.registry = registry;
        this.now = now;
    }

    /** @return 기다린 ms(로그용). 작업이 없으면 -1이고 기록하지 않는다. */
    public long record(Long jobId, QueuePath path) {
        LocalDateTime createdAt = processingJobRepository.findById(jobId).map(ProcessingJob::getCreatedAt).orElse(null);
        return recordKnownCreatedAt(createdAt, path);
    }

    /** 시작 경로가 이미 읽은 시각을 사용하며 DB에 다시 접근하지 않는다. */
    public long recordKnownCreatedAt(LocalDateTime createdAt, QueuePath path) {
        if (createdAt == null) {
            return -1;
        }
        // 시계가 어긋나 음수가 나오면 0으로 — 음수 대기는 지표를 망가뜨린다.
        long waitMs = Math.max(0, Duration.between(createdAt, now.get()).toMillis());
        Timer.builder("trova.queue.wait")
                .description("공유로 작업이 만들어진 뒤 분석이 시작되기까지의 대기")
                .tag("path", path.tag())
                .serviceLevelObjectives(BUCKETS)
                .register(registry)
                .record(Duration.ofMillis(waitMs));
        return waitMs;
    }
}
