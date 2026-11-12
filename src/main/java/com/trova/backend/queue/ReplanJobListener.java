package com.trova.backend.queue;

import com.trova.backend.service.TripReplanJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka에서 받은 재구성 작업을 이 스레드에서 끝까지 처리한 뒤 다음 메시지로 넘어간다(#396) — 처리 중 서버가 죽으면 커밋되지 않은
 * 같은 작업을 다시 받는다. 이미 끝난 작업이면 TripReplanJobService가 건너뛴다.
 * 동시 처리는 1 — 서버 안 대기열의 기본 스레드 수와 같게(운영 956MB).
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class ReplanJobListener {

    private final TripReplanJobService tripReplanJobService;

    public ReplanJobListener(TripReplanJobService tripReplanJobService) {
        this.tripReplanJobService = tripReplanJobService;
    }

    @KafkaListener(topics = KafkaReplanJobQueue.TOPIC, groupId = "trova-replan-jobs",
            concurrency = "${app.queue.kafka.replan-concurrency:1}")
    public void onMessage(String jobId) {
        tripReplanJobService.processNow(Long.valueOf(jobId));
    }
}
