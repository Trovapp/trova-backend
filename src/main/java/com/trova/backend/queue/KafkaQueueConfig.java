package com.trova.backend.queue;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** app.queue.type=kafka일 때만. async(기본)일 때는 이 빈들이 없어 Kafka에 연결하지 않는다. */
@Configuration
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaQueueConfig {

    // 파티션 2 = 동시 처리 2(리스너 concurrency). Aiven 무료 플랜도 토픽당 파티션 2까지라 그대로 옮길 수 있다.
    @Bean
    public NewTopic shareJobsTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(KafkaShareJobQueue.TOPIC).partitions(2).replicas(replicas).build();
    }

    /**
     * Spring Kafka 기본 오류 처리는 같은 메시지를 9번 더 돌린다. 분석은 실패를 작업 상태로 남기고 끝나므로 여기까지 오는 건
     * DB 오류 같은 경우뿐인데, 다시 돌리면 Gemini를 또 부를 수 있어 다시 돌리지 않고 로그만 남긴다(남은 작업은 OrphanedJobRecoveryService가 정리).
     */
    @Bean
    public CommonErrorHandler shareJobErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(0L, 0L));
    }
}
