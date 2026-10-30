package com.trova.backend.queue;

import com.trova.backend.service.PlaceExtractionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaShareJobQueueTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final PlaceExtractionService extraction = mock(PlaceExtractionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final KafkaShareJobQueue queue = new KafkaShareJobQueue(kafkaTemplate, extraction, meters, clock);

    private void brokerAccepts(String id) {
        when(kafkaTemplate.send(KafkaShareJobQueue.TOPIC, id, id))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void brokerFails(String id) {
        when(kafkaTemplate.send(KafkaShareJobQueue.TOPIC, id, id))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    private double fallbacks() {
        return meters.counter("trova.queue.fallback").count();
    }

    @Test
    void 작업_id를_키와_값으로_보내고_서버_안_대기열은_쓰지_않는다() {
        brokerAccepts("42");

        queue.enqueue(42L);

        verify(kafkaTemplate).send(KafkaShareJobQueue.TOPIC, "42", "42");
        verify(extraction, never()).process(anyLong());
        assertThat(fallbacks()).isZero();
    }

    @Test
    void 브로커가_받지_못하면_서버_안_대기열로_넘기고_센다() {
        brokerFails("42");

        queue.enqueue(42L);

        verify(extraction).process(42L);
        assertThat(fallbacks()).isEqualTo(1);
    }

    @Test
    void 실패_뒤_1분_동안은_Kafka를_건너뛰고_지나면_다시_보낸다() {
        brokerFails("42");
        brokerAccepts("43");
        brokerAccepts("44");

        queue.enqueue(42L);
        now.set(now.get().plus(Duration.ofSeconds(59)));
        queue.enqueue(43L);
        now.set(now.get().plus(Duration.ofSeconds(2)));
        queue.enqueue(44L);

        verify(kafkaTemplate, never()).send(KafkaShareJobQueue.TOPIC, "43", "43");
        verify(extraction).process(43L);
        verify(kafkaTemplate, times(1)).send(KafkaShareJobQueue.TOPIC, "44", "44");
        verify(extraction, never()).process(44L);
        assertThat(fallbacks()).isEqualTo(2);
    }

    @Test
    void 서버_안_대기열도_가득_차면_거절_예외가_그대로_나간다() {
        brokerFails("42");
        doThrow(new TaskRejectedException("full")).when(extraction).process(42L);

        assertThatThrownBy(() -> queue.enqueue(42L)).isInstanceOf(TaskRejectedException.class);
    }
}
