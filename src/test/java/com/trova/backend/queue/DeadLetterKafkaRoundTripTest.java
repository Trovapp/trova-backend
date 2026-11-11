package com.trova.backend.queue;

import com.trova.backend.service.TripPlannerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
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
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 리스너가 실패하면(DB 오류 흉내) 메시지가 실패 작업 토픽으로 가고, 같은 메시지를 다시 돌리지 않는지(#394).
 * 운영과 같은 오류 처리기(KafkaQueueConfig.shareJobErrorHandler)를 그대로 쓴다. DB·Gemini는 부르지 않는다.
 */
@EmbeddedKafka(topics = {KafkaDraftJobQueue.TOPIC, KafkaQueueConfig.DEAD_LETTERS_TOPIC}, partitions = 1)
class DeadLetterKafkaRoundTripTest {

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
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
            return new KafkaQueueConfig().shareJobErrorHandler(kafkaTemplate, meterRegistry);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
                @Value("${spring.embedded.kafka.brokers}") String brokers, CommonErrorHandler errorHandler) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(DraftJobKafkaRoundTripTest.consumerProps(brokers)));
            factory.setCommonErrorHandler(errorHandler);
            return factory;
        }

        @Bean
        TripPlannerService tripPlannerService() {
            TripPlannerService planner = mock(TripPlannerService.class);
            doThrow(new IllegalStateException("DB 연결 끊김")).when(planner).processNow(13L);
            return planner;
        }

        @Bean
        DraftJobListener draftJobListener(TripPlannerService planner) {
            return new DraftJobListener(planner);
        }
    }

    @Test
    void 리스너가_실패한_메시지는_실패_작업_토픽으로_가고_다시_돌리지_않는다(EmbeddedKafkaBroker broker) throws Exception {
        String brokers = broker.getBrokersAsString();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("embedded-kafka", Map.of("spring.embedded.kafka.brokers", brokers)));
            context.register(Config.class);
            context.refresh();

            context.getBean(KafkaTemplate.class).send(KafkaDraftJobQueue.TOPIC, "13", "13").get();

            List<ConsumerRecord<String, String>> dead = new ArrayList<>();
            Map<String, Object> props = new HashMap<>(DraftJobKafkaRoundTripTest.consumerProps(brokers));
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader");
            try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(props)) {
                reader.subscribe(List.of(KafkaQueueConfig.DEAD_LETTERS_TOPIC));
                long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
                while (dead.isEmpty() && System.nanoTime() < deadline) {
                    reader.poll(Duration.ofMillis(500)).forEach(dead::add);
                }
            }

            assertThat(dead).hasSize(1);
            assertThat(dead.get(0).value()).isEqualTo("13");
            assertThat(header(dead.get(0), "kafka_dlt-original-topic")).isEqualTo(KafkaDraftJobQueue.TOPIC);
            assertThat(header(dead.get(0), "kafka_dlt-exception-message")).contains("DB 연결 끊김");
            assertThat(dead.get(0).headers().lastHeader("kafka_dlt-exception-stacktrace")).as("스택 트레이스는 뺀다").isNull();

            // 다시 돌리지 않는다(Gemini 재호출 방지) — 잠깐 더 기다려도 1번뿐.
            Thread.sleep(2_000);
            verify(context.getBean(TripPlannerService.class), timeout(1_000).times(1)).processNow(13L);
            assertThat(context.getBean(MeterRegistry.class)
                    .counter("trova.queue.dead_letter", "topic", KafkaDraftJobQueue.TOPIC).count()).isEqualTo(1);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
