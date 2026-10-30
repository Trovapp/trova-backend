package com.trova.backend.queue;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Kafka에서 받은 작업을 이 스레드에서 끝까지 분석한 뒤에 다음 메시지로 넘어간다 — 오프셋은 처리 뒤에 커밋되므로
 * 처리 중 서버가 죽으면 같은 작업을 다시 받는다(async 방식은 이때 작업이 사라졌다, #43).
 * 동시 처리 수는 async 실행기의 기본 스레드 수(2)와 같게 둔다.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class ShareJobListener {

    private static final Logger log = LoggerFactory.getLogger(ShareJobListener.class);

    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;

    public ShareJobListener(ProcessingJobRepository processingJobRepository, PlaceExtractionService placeExtractionService) {
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
    }

    @KafkaListener(topics = KafkaShareJobQueue.TOPIC, groupId = "trova-share-jobs", concurrency = "${app.queue.kafka.concurrency:2}")
    public void onMessage(String jobId) {
        Long id = Long.valueOf(jobId);
        // 다시 받은 메시지가 이미 끝난(또는 지워진) 작업이면 건너뛴다 — 같은 영상을 두 번 분석하면 Gemini 한도를 두 번 쓴다.
        Optional<JobStatus> status = processingJobRepository.findById(id).map(ProcessingJob::getStatus);
        if (status.isEmpty() || status.get() == JobStatus.DONE || status.get() == JobStatus.FAILED) {
            log.info("ProcessingJob {} 대기열 메시지 건너뜀(상태 {})", id, status.map(Enum::name).orElse("없음"));
            return;
        }
        placeExtractionService.processNow(id, QueuePath.KAFKA);
    }
}
