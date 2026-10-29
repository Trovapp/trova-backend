package com.trova.backend.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 작업 id를 Kafka 토픽에 보낸다. 브로커가 받았다는 응답을 기다려서, 못 받으면 async 구현과 같은 TaskRejectedException으로 바꾼다 —
 * 부르는 쪽(공유·관리자 재시도)은 어느 구현인지 몰라도 작업을 지우고 503을 돌려준다.
 */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaShareJobQueue implements ShareJobQueue {

    public static final String TOPIC = "trova.share-jobs";
    // 브로커가 멀거나 꺼져 있을 때 공유 요청이 오래 붙잡히지 않게. 로컬·같은 지역 브로커는 수십 ms 안에 답한다.
    static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaShareJobQueue(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void enqueue(Long jobId) {
        String id = String.valueOf(jobId);
        try {
            // 키를 작업 id로 — 같은 작업의 메시지는 같은 파티션으로 가서 순서가 지켜진다.
            kafkaTemplate.send(TOPIC, id, id).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskRejectedException("작업 대기열에 넣지 못함: " + jobId, e);
        } catch (Exception e) {
            throw new TaskRejectedException("작업 대기열에 넣지 못함: " + jobId, e);
        }
    }
}
