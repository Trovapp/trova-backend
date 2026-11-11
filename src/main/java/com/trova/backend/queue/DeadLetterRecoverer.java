package com.trova.backend.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.util.List;

/**
 * 리스너에서 처리하지 못한 메시지를 실패 작업 토픽(trova.dead-letters)으로 보낸다(#394).
 * 분석·초안은 실패를 작업 상태(FAILED)로 남기고 끝나므로 여기까지 오는 건 DB 오류·잘못된 메시지 같은 경우뿐이다.
 * - 원래 토픽별로 지표 trova.queue.dead_letter{topic}을 센다(Grafana 경보).
 * - 실패 토픽으로 보내기마저 실패해도(브로커 장애) 예외를 밖으로 던지지 않는다 — 던지면 오류 처리기가 같은 메시지를 다시 돌려
 *   분석·초안이 Gemini를 또 부를 수 있다. 이때는 로그에만 남고, 멈춘 작업은 OrphanedJobRecoveryService가 15분 뒤 정리한다.
 */
public class DeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final ConsumerRecordRecoverer publisher;
    private final MeterRegistry meterRegistry;

    /**
     * @param topics 미리 0으로 만들어 둘 원래 토픽들 — 지표가 첫 실패 때 처음 생기면 Prometheus increase()가 그 1건을 0으로 본다.
     */
    public DeadLetterRecoverer(ConsumerRecordRecoverer publisher, MeterRegistry meterRegistry, List<String> topics) {
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
        topics.forEach(this::counter);
    }

    private Counter counter(String topic) {
        return Counter.builder("trova.queue.dead_letter")
                .description("리스너가 처리하지 못해 실패 작업 토픽으로 보낸 메시지 수")
                .tag("topic", topic)
                .register(meterRegistry);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        counter(record.topic()).increment();
        log.error("Kafka 처리 실패 → 실패 작업 토픽으로: topic={} partition={} offset={} value={} 예외={}",
                record.topic(), record.partition(), record.offset(), record.value(), exception.toString());
        try {
            publisher.accept(record, exception);
        } catch (RuntimeException e) {
            log.error("실패 작업 토픽으로도 보내지 못함(다시 돌리지 않음): topic={} offset={} value={} {}",
                    record.topic(), record.offset(), record.value(), e.toString());
        }
    }
}
