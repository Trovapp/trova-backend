package com.trova.backend.queue;

import com.trova.backend.service.PlaceExtractionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * Kafka에서 받은 작업을 이 스레드에서 끝까지 분석한 뒤에 다음 메시지로 넘어간다 — 오프셋은 처리 뒤에 커밋되므로
 * 처리 중 서버가 죽으면 같은 작업을 다시 받는다(async 방식은 이때 작업이 사라졌다, #43).
 * 동시 처리 수는 async 실행기의 기본 스레드 수(2)와 같게 둔다.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class ShareJobListener {

    private final PlaceExtractionService placeExtractionService;

    public ShareJobListener(PlaceExtractionService placeExtractionService) {
        this.placeExtractionService = placeExtractionService;
    }

    @KafkaListener(topics = KafkaShareJobQueue.TOPIC, groupId = "trova-share-jobs", concurrency = "${app.queue.kafka.concurrency:2}")
    public void onMessage(String jobId) {
        Long id = Long.valueOf(jobId);
        // 완료/실패/삭제 확인은 서비스의 작업 잠금 안에서 한 번만 한다(ADR0024).
        placeExtractionService.processNow(id, QueuePath.KAFKA);
    }
}
