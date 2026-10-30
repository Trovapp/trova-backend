package com.trova.backend.queue;

import com.trova.backend.service.PlaceExtractionService;
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
 * 작업 id를 Kafka 토픽에 보낸다. 브로커가 받지 못하면(꺼짐·무료 브로커 정지·네트워크) 공유를 막지 않고 서버 안 @Async 대기열로
 * 넘긴다(ADR 0021) — 사용자는 차이를 모르고, 넘긴 횟수는 지표 trova.queue.fallback으로 남는다.
 * 한 번 실패하면 1분 동안은 Kafka를 건너뛴다 — 브로커가 꺼진 동안 공유마다 보내기 대기(최대 5초)를 다시 겪지 않게.
 * @Async 대기열도 가득 차면 TaskRejectedException이 그대로 나가고, 부르는 쪽이 작업을 지우고 503으로 알린다(#31).
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaShareJobQueue implements ShareJobQueue {

    private static final Logger log = LoggerFactory.getLogger(KafkaShareJobQueue.class);

    public static final String TOPIC = "trova.share-jobs";
    // 브로커가 멀거나 꺼져 있을 때 공유 요청이 오래 붙잡히지 않게. 로컬·같은 지역 브로커는 수십 ms 안에 답한다.
    static final long SEND_TIMEOUT_SECONDS = 5;
    static final Duration SKIP_AFTER_FAILURE = Duration.ofMinutes(1);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PlaceExtractionService placeExtractionService;
    private final Counter fallbackCounter;
    private final Clock clock;
    private volatile Instant kafkaSkippedUntil = Instant.MIN;

    public KafkaShareJobQueue(KafkaTemplate<String, String> kafkaTemplate, PlaceExtractionService placeExtractionService,
                              MeterRegistry meterRegistry, Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.placeExtractionService = placeExtractionService;
        this.fallbackCounter = Counter.builder("trova.queue.fallback")
                .description("Kafka에 넣지 못해 서버 안 대기열로 넘긴 영상 분석 작업 수")
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
            // 키를 작업 id로 — 같은 작업의 메시지는 같은 파티션으로 가서 순서가 지켜진다.
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
        log.warn("ProcessingJob {} Kafka에 넣지 못해 서버 안 대기열로 넘김, {}간 Kafka 건너뜀: {}", jobId, SKIP_AFTER_FAILURE, e.toString());
        fallBack(jobId);
    }

    private void fallBack(Long jobId) {
        fallbackCounter.increment();
        placeExtractionService.process(jobId);
    }
}
