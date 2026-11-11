package com.trova.backend.queue;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;

/** app.queue.type=kafka일 때만. async(기본)일 때는 이 빈들이 없어 Kafka에 연결하지 않는다. */
@Configuration
@ConditionalOnProperty(name = "app.queue.type", havingValue = "kafka")
public class KafkaQueueConfig {

    public static final String DEAD_LETTERS_TOPIC = "trova.dead-letters";

    // 파티션 2 = 동시 처리 2(리스너 concurrency). Aiven 무료 플랜도 토픽당 파티션 2까지라 그대로 옮길 수 있다.
    @Bean
    public NewTopic shareJobsTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(KafkaShareJobQueue.TOPIC).partitions(2).replicas(replicas).build();
    }

    // 일정 초안(#390). 동시 처리는 1이지만 무료 플랜 상한(2)에 맞춰 두면 나중에 설정만 바꿔 늘릴 수 있다.
    @Bean
    public NewTopic draftJobsTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(KafkaDraftJobQueue.TOPIC).partitions(2).replicas(replicas).build();
    }

    // 실패 작업 토픽(#394). 원래 토픽이 여러 개라 하나로 모은다(원래 토픽은 헤더 kafka_dlt-original-topic). 무료 토픽 3개째.
    @Bean
    public NewTopic deadLettersTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(DEAD_LETTERS_TOPIC).partitions(1).replicas(replicas).build();
    }

    /**
     * Spring Kafka 기본 오류 처리는 같은 메시지를 9번 더 돌린다. 분석·초안은 실패를 작업 상태로 남기고 끝나므로 여기까지 오는 건
     * DB 오류 같은 경우뿐인데, 다시 돌리면 Gemini를 또 부를 수 있어 다시 돌리지 않는다.
     * 대신 실패 작업 토픽으로 보내 나중에 찾을 수 있게 하고 지표로 센다(#394). 남은 작업은 OrphanedJobRecoveryService가 정리.
     * 스택 트레이스 헤더는 빼고(무료 브로커 125KiB/s) 예외 종류·메시지만 남긴다.
     */
    @Bean
    public CommonErrorHandler shareJobErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTERS_TOPIC, -1));
        publisher.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        return new DefaultErrorHandler(new DeadLetterRecoverer(publisher, meterRegistry,
                List.of(KafkaShareJobQueue.TOPIC, KafkaDraftJobQueue.TOPIC)), new FixedBackOff(0L, 0L));
    }
}
