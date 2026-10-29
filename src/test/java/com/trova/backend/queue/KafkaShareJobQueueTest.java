package com.trova.backend.queue;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaShareJobQueueTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void 작업_id를_키와_값으로_보낸다() {
        when(kafkaTemplate.send(KafkaShareJobQueue.TOPIC, "42", "42"))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThatCode(() -> new KafkaShareJobQueue(kafkaTemplate).enqueue(42L)).doesNotThrowAnyException();
        verify(kafkaTemplate).send(KafkaShareJobQueue.TOPIC, "42", "42");
    }

    @Test
    void 브로커가_받지_못하면_async와_같은_거절_예외로_바꾼다() {
        when(kafkaTemplate.send(KafkaShareJobQueue.TOPIC, "42", "42"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> new KafkaShareJobQueue(kafkaTemplate).enqueue(42L))
                .isInstanceOf(TaskRejectedException.class);
    }
}
