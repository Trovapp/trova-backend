package com.trova.backend.queue;

import com.trova.backend.service.TripPlannerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka에서 받은 초안을 이 스레드에서 끝까지 만든 뒤 다음 메시지로 넘어간다(#390) — 처리 중 서버가 죽으면 커밋되지 않은
 * 같은 초안을 다시 받는다. 이미 끝난 초안이면 TripPlannerService가 Gemini를 부르지 않고 건너뛴다.
 * 동시 처리는 1 — 초안은 Gemini를 여러 번 부르고 운영 서버(956MB)가 빠듯해서, 서버 안 대기열의 기본 스레드 수(1)와 같게.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class DraftJobListener {

    private final TripPlannerService tripPlannerService;

    public DraftJobListener(TripPlannerService tripPlannerService) {
        this.tripPlannerService = tripPlannerService;
    }

    @KafkaListener(topics = KafkaDraftJobQueue.TOPIC, groupId = "trova-draft-jobs",
            concurrency = "${app.queue.kafka.draft-concurrency:1}")
    public void onMessage(String draftId) {
        tripPlannerService.processNow(Long.valueOf(draftId));
    }
}
