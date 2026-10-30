package com.trova.backend.queue;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 내장 Kafka 브로커로 보내기 → 리스너 → 분석 호출까지. 컨텍스트는 테스트 안에서 직접 만들고 닫는다 —
 * 테스트 컨텍스트 캐시에 하나라도 더 들어가면 다른 Boot 컨텍스트가 밀려나 닫히면서 공용 H2 스키마를 지운다
 * (#240 기록, 이 테스트를 처음 @SpringJUnitConfig로 썼을 때도 ApiSessionCreationIntegrationTest 2개가 "USERS 없음"으로 실패).
 */
@EmbeddedKafka(topics = KafkaShareJobQueue.TOPIC, partitions = 2)
class ShareJobKafkaRoundTripTest {

    @Configuration
    @EnableKafka
    static class Config {

        @Bean
        KafkaTemplate<String, String> kafkaTemplate(@Value("${spring.embedded.kafka.brokers}") String brokers) {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                @Value("${spring.embedded.kafka.brokers}") String brokers) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)));
            return factory;
        }

        @Bean
        ProcessingJobRepository processingJobRepository() {
            ProcessingJob job = mock(ProcessingJob.class);
            when(job.getStatus()).thenReturn(JobStatus.PENDING);
            ProcessingJobRepository repository = mock(ProcessingJobRepository.class);
            when(repository.findById(42L)).thenReturn(Optional.of(job));
            return repository;
        }

        @Bean
        PlaceExtractionService placeExtractionService() {
            return mock(PlaceExtractionService.class);
        }

        @Bean
        KafkaShareJobQueue shareJobQueue(KafkaTemplate<String, String> kafkaTemplate, PlaceExtractionService extraction) {
            return new KafkaShareJobQueue(kafkaTemplate, extraction, new SimpleMeterRegistry(), Clock.systemUTC());
        }

        @Bean
        ShareJobListener shareJobListener(ProcessingJobRepository repository, PlaceExtractionService extraction) {
            return new ShareJobListener(repository, extraction);
        }
    }

    @Test
    void 대기열에_넣은_작업을_리스너가_받아_분석한다(EmbeddedKafkaBroker broker) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("embedded-kafka", Map.of("spring.embedded.kafka.brokers", broker.getBrokersAsString())));
            context.register(Config.class);
            context.refresh();

            context.getBean(KafkaShareJobQueue.class).enqueue(42L);

            verify(context.getBean(PlaceExtractionService.class), timeout(30_000)).processNow(42L);
            // Kafka로 갔으면 서버 안 대기열(process)로는 넘기지 않는다.
            verify(context.getBean(PlaceExtractionService.class), never()).process(42L);
        }
    }
}
