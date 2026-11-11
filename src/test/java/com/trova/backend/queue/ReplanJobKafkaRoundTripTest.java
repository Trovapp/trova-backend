package com.trova.backend.queue;

import com.trova.backend.service.TripReplanJobService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 내장 Kafka로 재구성 작업 보내기 → 리스너 → 재구성 작업 만들기(#396). 컨텍스트는 ShareJobKafkaRoundTripTest와 같은 이유로 테스트 안에서 만들고 닫는다.
 * TripReplanJobService는 가짜라 DB·Gemini는 부르지 않는다.
 */
@EmbeddedKafka(topics = KafkaReplanJobQueue.TOPIC, partitions = 2)
class ReplanJobKafkaRoundTripTest {

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
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerProps(brokers)));
            return factory;
        }

        @Bean
        TripReplanJobService tripPlannerService() {
            return mock(TripReplanJobService.class);
        }

        @Bean
        KafkaReplanJobQueue replanJobQueue(KafkaTemplate<String, String> kafkaTemplate, TripReplanJobService planner) {
            return new KafkaReplanJobQueue(kafkaTemplate, planner, new SimpleMeterRegistry(), Clock.systemUTC());
        }

        @Bean
        ReplanJobListener replanJobListener(TripReplanJobService planner) {
            return new ReplanJobListener(planner);
        }
    }

    static Map<String, Object> consumerProps(String brokers) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
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
    void 대기열에_넣은_재구성_작업을_리스너가_받아_만든다(EmbeddedKafkaBroker broker) {
        try (AnnotationConfigApplicationContext context = start(broker)) {
            context.getBean(KafkaReplanJobQueue.class).enqueue(9L);

            TripReplanJobService planner = context.getBean(TripReplanJobService.class);
            verify(planner, timeout(30_000)).processNow(9L);
            // Kafka로 갔으면 서버 안 대기열(process)로는 넘기지 않는다.
            verify(planner, never()).process(9L);
        }
    }

    /**
     * 재구성 작업을 받은 서버가 끝내기 전에 죽은 경우 — 같은 그룹의 소비자가 메시지를 받고 커밋하지 않은 채 닫힌 뒤,
     * 새로 뜬 서버(리스너)가 같은 재구성 작업을 다시 받는지. 실제 프로세스 강제 종료 대신 커밋 없는 소비자 종료로 흉내 낸다.
     */
    @Test
    void 받고_끝내기_전에_서버가_죽으면_새_서버가_같은_재구성_작업을_다시_받는다(EmbeddedKafkaBroker broker) {
        String brokers = broker.getBrokersAsString();
        DefaultKafkaProducerFactory<String, String> producers = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        new KafkaTemplate<>(producers).send(KafkaReplanJobQueue.TOPIC, "77", "77").join();
        producers.destroy();

        Map<String, Object> props = new java.util.HashMap<>(consumerProps(brokers));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "trova-replan-jobs");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> dying = new KafkaConsumer<>(props)) {
            dying.subscribe(List.of(KafkaReplanJobQueue.TOPIC));
            List<String> received = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!received.contains("77") && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = dying.poll(Duration.ofMillis(500));
                records.forEach(r -> received.add(r.value()));
            }
            assertThat(received).as("첫 서버가 재구성 작업을 받음").contains("77");
            // 커밋하지 않고 닫는다 = 재구성 작업을 만드는 중에 서버가 죽음.
        }

        try (AnnotationConfigApplicationContext context = start(broker)) {
            verify(context.getBean(TripReplanJobService.class), timeout(60_000)).processNow(77L);
        }
    }
}
