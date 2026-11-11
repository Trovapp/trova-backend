package com.trova.backend.queue;

import com.trova.backend.service.TripPlannerService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDraftJobQueueTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final TripPlannerService planner = mock(TripPlannerService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T00:00:00Z"));
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
    private final KafkaDraftJobQueue queue = new KafkaDraftJobQueue(kafkaTemplate, planner, meters, clock);

    private void brokerAccepts(String id) {
        when(kafkaTemplate.send(KafkaDraftJobQueue.TOPIC, id, id))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void brokerFails(String id) {
        when(kafkaTemplate.send(KafkaDraftJobQueue.TOPIC, id, id))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    private double fallbacks() {
        return meters.counter("trova.queue.draft.fallback").count();
    }

    @Test
    void 초안_id를_초안_토픽에_보내고_서버_안_대기열은_쓰지_않는다() {
        brokerAccepts("9");

        queue.enqueue(9L);

        verify(kafkaTemplate).send(KafkaDraftJobQueue.TOPIC, "9", "9");
        verify(planner, never()).process(anyLong());
        assertThat(fallbacks()).isZero();
    }

    @Test
    void 브로커가_받지_못하면_서버_안_대기열로_넘기고_1분_동안_건너뛴다() {
        brokerFails("9");
        brokerAccepts("11");

        queue.enqueue(9L);
        queue.enqueue(10L);
        now.set(now.get().plus(Duration.ofSeconds(61)));
        queue.enqueue(11L);

        verify(planner).process(9L);
        verify(planner).process(10L);
        verify(kafkaTemplate, never()).send(KafkaDraftJobQueue.TOPIC, "10", "10");
        verify(planner, never()).process(11L);
        assertThat(fallbacks()).isEqualTo(2);
    }

    @Test
    void 서버_안_대기열도_가득_차면_거절_예외가_그대로_나간다() {
        brokerFails("9");
        doThrow(new TaskRejectedException("full")).when(planner).process(9L);

        assertThatThrownBy(() -> queue.enqueue(9L)).isInstanceOf(TaskRejectedException.class);
    }
}
