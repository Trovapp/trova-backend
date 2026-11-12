package com.trova.backend.queue;

import com.trova.backend.service.TripReplanJobService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 재구성 작업 id를 Kafka 토픽에 보낸다(#396). 공유 대기열(KafkaShareJobQueue)과 같은 규칙이다 — 브로커가 받지 못하면 서버 안 대기열로
 * 넘기고(지표 trova.queue.replan.fallback), 한 번 실패하면 1분 동안 Kafka를 건너뛴다.
 * 사용자가 화면에서 기다리는 요청 안에서 부르므로 보내기 대기는 최대 5초.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaReplanJobQueue implements ReplanJobQueue {

    private static final Logger log = LoggerFactory.getLogger(KafkaReplanJobQueue.class);

    // Aiven 무료 플랜은 토픽 5개까지 — 이걸로 4개째.
    public static final String TOPIC = "trova.replan-jobs";
    static final long SEND_TIMEOUT_SECONDS = 5;
    static final Duration SKIP_AFTER_FAILURE = Duration.ofMinutes(1);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TripReplanJobService tripReplanJobService;
    private final Counter fallbackCounter;
    private final Clock clock;
    private volatile Instant kafkaSkippedUntil = Instant.MIN;

    public KafkaReplanJobQueue(KafkaTemplate<String, String> kafkaTemplate, TripReplanJobService tripReplanJobService,
                              MeterRegistry meterRegistry, Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.tripReplanJobService = tripReplanJobService;
        this.fallbackCounter = Counter.builder("trova.queue.replan.fallback")
                .description("Kafka에 넣지 못해 서버 안 대기열로 넘긴 일정 재구성 작업 수")
                .register(meterRegistry);
        this.clock = clock;
    }

    @Override
    public void enqueue(Long jobId) {
        if (clock.instant().isBefore(kafkaSkippedUntil)) {
            fallBack(jobId);
            return;
        }
        String id = String.valueOf(jobId);
        try {
            kafkaTemplate.send(TOPIC, id, id).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onSendFailure(jobId, e);
        } catch (Exception e) {
            onSendFailure(jobId, e);
        }
    }

    private void onSendFailure(Long jobId, Exception e) {
        kafkaSkippedUntil = clock.instant().plus(SKIP_AFTER_FAILURE);
        log.warn("TripReplanJob {} Kafka에 넣지 못해 서버 안 대기열로 넘김, {}간 Kafka 건너뜀: {}", jobId, SKIP_AFTER_FAILURE, e.toString());
        fallBack(jobId);
    }

    private void fallBack(Long jobId) {
        fallbackCounter.increment();
        tripReplanJobService.process(jobId);
    }
}
