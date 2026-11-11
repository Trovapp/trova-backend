package com.trova.backend.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DeadLetterRecovererTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private double count(String topic) {
        return meters.counter("trova.queue.dead_letter", "topic", topic).count();
    }

    @Test
    void 원래_토픽별로_세고_실패_작업_토픽으로_넘긴다() {
        List<Object> published = new ArrayList<>();
        ConsumerRecordRecoverer publisher = (record, e) -> published.add(record.value());
        DeadLetterRecoverer recoverer = new DeadLetterRecoverer(publisher, meters, List.of(KafkaShareJobQueue.TOPIC, KafkaDraftJobQueue.TOPIC));

        recoverer.accept(new ConsumerRecord<>(KafkaShareJobQueue.TOPIC, 0, 5L, "42", "42"), new IllegalStateException("db"));
        recoverer.accept(new ConsumerRecord<>(KafkaDraftJobQueue.TOPIC, 1, 7L, "9", "9"), new IllegalStateException("db"));
        recoverer.accept(new ConsumerRecord<>(KafkaDraftJobQueue.TOPIC, 1, 8L, "10", "10"), new IllegalStateException("db"));

        assertThat(published).containsExactly("42", "9", "10");
        assertThat(count(KafkaShareJobQueue.TOPIC)).isEqualTo(1);
        assertThat(count(KafkaDraftJobQueue.TOPIC)).isEqualTo(2);
    }

    @Test
    void 실패_작업_토픽으로_보내지_못해도_예외를_던지지_않는다_다시_돌리면_Gemini를_또_부르므로() {
        ConsumerRecordRecoverer publisher = (record, e) -> {
            throw new org.springframework.kafka.KafkaException("broker down");
        };
        DeadLetterRecoverer recoverer = new DeadLetterRecoverer(publisher, meters, List.of(KafkaShareJobQueue.TOPIC, KafkaDraftJobQueue.TOPIC));

        assertThatCode(() -> recoverer.accept(new ConsumerRecord<>(KafkaShareJobQueue.TOPIC, 0, 5L, "42", "42"),
                new IllegalStateException("db"))).doesNotThrowAnyException();
        assertThat(count(KafkaShareJobQueue.TOPIC)).isEqualTo(1);
    }

    @Test
    void 서버가_뜰_때_토픽별_지표를_0으로_미리_만든다_첫_실패를_increase가_놓치지_않게() {
        new DeadLetterRecoverer((record, e) -> { }, meters, List.of(KafkaShareJobQueue.TOPIC, KafkaDraftJobQueue.TOPIC));

        assertThat(meters.find("trova.queue.dead_letter").counters()).hasSize(2);
        assertThat(count(KafkaShareJobQueue.TOPIC)).isZero();
    }
}
