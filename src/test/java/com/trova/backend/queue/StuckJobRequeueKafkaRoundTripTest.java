package com.trova.backend.queue;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.service.PlaceExtractionService;
import com.trova.backend.service.StuckJobRequeueService;
import com.trova.backend.service.TripPlannerService;
import com.trova.backend.service.TripReplanJobService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR 0043 틈 a — 작업 행은 저장(커밋)됐는데 Kafka로 보내기 전에 서버가 죽은 경우. 운영에서 그 순간을 맞추기 어려워 여기서 재현한다.
 * 대기열에는 아무것도 넣지 않고(= 보내기 전에 죽음), 저장소가 "3분 넘게 PENDING / 8분 넘게 PROCESSING인 작업"으로 id를 돌려주게 한 뒤
 * 다시 보내기를 한 번 돌리면, 실제 Kafka 대기열 → 내장 브로커 → 리스너 → processNow까지 가는지 본다.
 * 처리 서비스는 가짜라 DB·Gemini는 부르지 않는다. 컨텍스트는 ShareJobKafkaRoundTripTest와 같은 이유로 테스트 안에서 만들고 닫는다.
 */
@EmbeddedKafka(topics = {KafkaShareJobQueue.TOPIC, KafkaDraftJobQueue.TOPIC, KafkaReplanJobQueue.TOPIC}, partitions = 2)
class StuckJobRequeueKafkaRoundTripTest {

    private static final long SHARE_JOB = 42L;
    private static final long DRAFT = 7L;
    private static final long REPLAN_JOB = 9L;

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
        PlaceExtractionService placeExtractionService() {
            return mock(PlaceExtractionService.class);
        }

        @Bean
        TripPlannerService tripPlannerService() {
            return mock(TripPlannerService.class);
        }

        @Bean
        TripReplanJobService tripReplanJobService() {
            return mock(TripReplanJobService.class);
        }

        @Bean
        KafkaShareJobQueue shareJobQueue(KafkaTemplate<String, String> kafkaTemplate, PlaceExtractionService extraction) {
            return new KafkaShareJobQueue(kafkaTemplate, extraction, new SimpleMeterRegistry(), Clock.systemUTC());
        }

        @Bean
        KafkaDraftJobQueue draftJobQueue(KafkaTemplate<String, String> kafkaTemplate, TripPlannerService planner) {
            return new KafkaDraftJobQueue(kafkaTemplate, planner, new SimpleMeterRegistry(), Clock.systemUTC());
        }

        @Bean
        KafkaReplanJobQueue replanJobQueue(KafkaTemplate<String, String> kafkaTemplate, TripReplanJobService replan) {
            return new KafkaReplanJobQueue(kafkaTemplate, replan, new SimpleMeterRegistry(), Clock.systemUTC());
        }

        @Bean
        ShareJobListener shareJobListener(PlaceExtractionService extraction) {
            return new ShareJobListener(extraction);
        }

        @Bean
        DraftJobListener draftJobListener(TripPlannerService planner) {
            return new DraftJobListener(planner);
        }

        @Bean
        ReplanJobListener replanJobListener(TripReplanJobService replan) {
            return new ReplanJobListener(replan);
        }

        // 저장은 됐지만 대기열에 없는 작업: 영상 분석은 PENDING, 초안은 PROCESSING, 재구성은 PENDING으로 멈춰 있다.
        @Bean
        ProcessingJobRepository processingJobRepository() {
            ProcessingJobRepository repository = mock(ProcessingJobRepository.class);
            when(repository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
            when(repository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(SHARE_JOB));
            return repository;
        }

        @Bean
        TripDraftRepository tripDraftRepository() {
            TripDraftRepository repository = mock(TripDraftRepository.class);
            when(repository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
            when(repository.findIdsByStatusAndUpdatedAtBetween(eq(TripDraftStatus.PROCESSING), any(), any())).thenReturn(List.of(DRAFT));
            return repository;
        }

        @Bean
        TripReplanJobRepository tripReplanJobRepository() {
            TripReplanJobRepository repository = mock(TripReplanJobRepository.class);
            when(repository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
            when(repository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(REPLAN_JOB));
            return repository;
        }

        @Bean
        StuckJobRequeueService stuckJobRequeueService(
                ProcessingJobRepository processingJobRepository, TripDraftRepository tripDraftRepository,
                TripReplanJobRepository tripReplanJobRepository, KafkaShareJobQueue shareJobQueue,
                KafkaDraftJobQueue draftJobQueue, KafkaReplanJobQueue replanJobQueue
        ) {
            return new StuckJobRequeueService(processingJobRepository, tripDraftRepository, tripReplanJobRepository,
                    shareJobQueue, draftJobQueue, replanJobQueue, new SimpleMeterRegistry());
        }
    }

    private AnnotationConfigApplicationContext start(EmbeddedKafkaBroker broker) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("embedded-kafka", Map.of("spring.embedded.kafka.brokers", broker.getBrokersAsString())));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test
    void 저장만_되고_보내지_못한_작업을_다시_보내기가_Kafka로_보내_리스너가_처리한다(EmbeddedKafkaBroker broker) {
        try (AnnotationConfigApplicationContext context = start(broker)) {
            PlaceExtractionService extraction = context.getBean(PlaceExtractionService.class);
            TripPlannerService planner = context.getBean(TripPlannerService.class);
            TripReplanJobService replan = context.getBean(TripReplanJobService.class);

            // 스케줄러가 1분마다 부르는 것과 같은 진입점.
            context.getBean(StuckJobRequeueService.class).requeueStuckJobs();

            verify(extraction, timeout(30_000)).processNow(SHARE_JOB, QueuePath.KAFKA);
            verify(planner, timeout(30_000)).processNow(DRAFT);
            verify(replan, timeout(30_000)).processNow(REPLAN_JOB);
            // 브로커가 정상이면 서버 안 대기열로는 넘기지 않는다.
            verify(extraction, never()).process(SHARE_JOB);
            verify(planner, never()).process(DRAFT);
            verify(replan, never()).process(REPLAN_JOB);
        }
    }
}
