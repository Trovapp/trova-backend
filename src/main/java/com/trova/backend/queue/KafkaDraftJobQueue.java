package com.trova.backend.queue;

import com.trova.backend.service.TripPlannerService;
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
 * 초안 id를 Kafka 토픽에 보낸다(#390). 공유 대기열(KafkaShareJobQueue)과 같은 규칙이다 — 브로커가 받지 못하면 서버 안 대기열로
 * 넘기고(지표 trova.queue.draft.fallback), 한 번 실패하면 1분 동안 Kafka를 건너뛴다.
 * 영상 분석 스레드(Kafka 리스너)가 분석 직후 부르므로, 여기서 오래 기다리면 다음 분석이 늦어진다 — 보내기 대기는 최대 5초.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaDraftJobQueue implements DraftJobQueue {

    private static final Logger log = LoggerFactory.getLogger(KafkaDraftJobQueue.class);

    // Aiven 무료 플랜은 토픽 5개까지 — 이걸로 2개째.
    public static final String TOPIC = "trova.draft-jobs";
    static final long SEND_TIMEOUT_SECONDS = 5;
    static final Duration SKIP_AFTER_FAILURE = Duration.ofMinutes(1);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TripPlannerService tripPlannerService;
    private final Counter fallbackCounter;
    private final Clock clock;
    private volatile Instant kafkaSkippedUntil = Instant.MIN;

    public KafkaDraftJobQueue(KafkaTemplate<String, String> kafkaTemplate, TripPlannerService tripPlannerService,
                              MeterRegistry meterRegistry, Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.tripPlannerService = tripPlannerService;
        this.fallbackCounter = Counter.builder("trova.queue.draft.fallback")
                .description("Kafka에 넣지 못해 서버 안 대기열로 넘긴 일정 초안 작업 수")
                .register(meterRegistry);
        this.clock = clock;
    }

    @Override
    public void enqueue(Long draftId) {
        if (clock.instant().isBefore(kafkaSkippedUntil)) {
            fallBack(draftId);
            return;
        }
        String id = String.valueOf(draftId);
        try {
            kafkaTemplate.send(TOPIC, id, id).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onSendFailure(draftId, e);
        } catch (Exception e) {
            onSendFailure(draftId, e);
        }
    }

    private void onSendFailure(Long draftId, Exception e) {
        kafkaSkippedUntil = clock.instant().plus(SKIP_AFTER_FAILURE);
        log.warn("TripDraft {} Kafka에 넣지 못해 서버 안 대기열로 넘김, {}간 Kafka 건너뜀: {}", draftId, SKIP_AFTER_FAILURE, e.toString());
        fallBack(draftId);
    }

    private void fallBack(Long draftId) {
        fallbackCounter.increment();
        tripPlannerService.process(draftId);
    }
}
