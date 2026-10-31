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

            verify(context.getBean(PlaceExtractionService.class), timeout(30_000)).processNow(42L, QueuePath.KAFKA);
            // Kafka로 갔으면 서버 안 대기열(process)로는 넘기지 않는다.
            verify(context.getBean(PlaceExtractionService.class), never()).process(42L);
        }
    }

    /** 명시적으로 실행할 때만 기록하는 로컬 전달 지연 비교. DB·Gemini·운영 브로커를 쓰지 않는다. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TROVA_RUN_QUEUE_BENCH", matches = "1")
    void 로컬_async와_Kafka_전달_지연을_비교한다(EmbeddedKafkaBroker broker) throws Exception {
        java.util.concurrent.atomic.AtomicLong started = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.BlockingQueue<Long> arrivals = new java.util.concurrent.LinkedBlockingQueue<>();
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) new com.trova.backend.config.AsyncConfig().pipelineTaskExecutor();
        java.util.List<Map<String, Object>> samples = new java.util.ArrayList<>();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("embedded-kafka",
                    Map.of("spring.embedded.kafka.brokers", broker.getBrokersAsString())));
            context.register(Config.class);
            context.refresh();
            PlaceExtractionService extraction = context.getBean(PlaceExtractionService.class);
            org.mockito.Mockito.doAnswer(invocation -> {
                arrivals.add(System.nanoTime() - started.get());
                return null;
            }).when(extraction).processNow(42L, QueuePath.KAFKA);
            org.mockito.Mockito.doAnswer(invocation -> {
                arrivals.add(System.nanoTime() - started.get());
                return null;
            }).when(extraction).process(42L);
            KafkaShareJobQueue kafka = context.getBean(KafkaShareJobQueue.class);
            for (int round = 0; round <= 20; round++) {
                java.util.List<String> order = round % 2 == 0 ? java.util.List.of("async", "kafka") : java.util.List.of("kafka", "async");
                for (String path : order) {
                    started.set(System.nanoTime());
                    if (path.equals("kafka")) kafka.enqueue(42L);
                    else executor.execute(() -> extraction.process(42L));
                    Long latency = arrivals.poll(30, java.util.concurrent.TimeUnit.SECONDS);
                    org.assertj.core.api.Assertions.assertThat(latency).as("전달 완료").isNotNull();
                    samples.add(Map.of("path", path, "round", round, "warmup", round == 0, "milliseconds", latency / 1_000_000.0));
                }
            }
            java.nio.file.Path output = java.nio.file.Path.of("docs/bench/results/local-queue-latency-20261031.json");
            java.nio.file.Files.createDirectories(output.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "environment", "local Mac, embedded Kafka, 2 partitions/concurrency 2, async application pool, serial handoff",
                    "scope", "delivery only; mocked repository/extraction; no DB, network Aiven, Gemini, load or restart test",
                    "results", samples));
            verify(extraction, org.mockito.Mockito.times(21)).process(42L);
            verify(extraction, org.mockito.Mockito.times(21)).processNow(42L, QueuePath.KAFKA);
        } finally {
            executor.shutdown();
        }
    }
}
