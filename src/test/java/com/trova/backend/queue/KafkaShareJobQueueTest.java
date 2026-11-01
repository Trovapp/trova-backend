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

    /** 전송 ACK 지연 시나리오 재현. 실제 브로커·DB·Gemini·저장은 실행하지 않는다. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TROVA_REPRODUCE_LATE_ACK", matches = "1")
    void 전송_대기_시간초과_후_늦은_메시지가_두_실행경로에_진입한다() throws Exception {
        class DelayedAck extends CompletableFuture<SendResult<String, String>> {
            @Override
            public SendResult<String, String> get(long timeout, java.util.concurrent.TimeUnit unit)
                    throws java.util.concurrent.TimeoutException {
                throw new java.util.concurrent.TimeoutException("controlled late ack");
            }
        }
        DelayedAck ack = new DelayedAck();
        when(kafkaTemplate.send(KafkaShareJobQueue.TOPIC, "42", "42")).thenReturn(ack);
        queue.enqueue(42L);
        verify(extraction).process(42L);
        assertThat(ack.isCancelled()).isFalse();

        boolean acceptedLate = ack.complete(mock(SendResult.class));
        com.trova.backend.entity.ProcessingJob job = mock(com.trova.backend.entity.ProcessingJob.class);
        when(job.getStatus()).thenReturn(com.trova.backend.entity.JobStatus.PROCESSING);
        com.trova.backend.repository.ProcessingJobRepository repository = mock(com.trova.backend.repository.ProcessingJobRepository.class);
        when(repository.findById(42L)).thenReturn(java.util.Optional.of(job));
        // async가 진행 중인 동안 도착한 브로커 메시지를 리스너에 전달한다.
        new ShareJobListener(extraction).onMessage("42");
        verify(extraction).processNow(42L, QueuePath.KAFKA);
        assertThat(acceptedLate).isTrue();
        assertThat(fallbacks()).isEqualTo(1);

        java.nio.file.Path output = java.nio.file.Path.of("docs/bench/results/kafka-late-ack-reproduction-20261031.json");
        java.nio.file.Files.createDirectories(output.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), java.util.Map.of(
                "scenario", "controlled ACK timeout, async fallback in progress, then late Kafka delivery",
                "lateAckAccepted", acceptedLate, "asyncDispatches", 1, "kafkaDispatches", 1,
                "statusAtLateDelivery", "PROCESSING",
                "scope", "real queue/listener branches with mocked future/repository/extraction; duplicate execution entry, not actual duplicate Gemini calls or saved rows"));
    }
}
