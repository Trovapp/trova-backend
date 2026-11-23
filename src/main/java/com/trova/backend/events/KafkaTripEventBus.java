package com.trova.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.trip-events.type", havingValue = "kafka")
class KafkaTripEventBus implements TripEventBus, ConsumerSeekAware {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KafkaTripEventBus.class);
    static final String TOPIC = "trova.trip-events";
    record Envelope(String origin, TripChangedEvent event) {}
    private final String origin = UUID.randomUUID().toString();
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper();
    private final TripStreams streams;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String consumerGroup;
    private final Executor sender;
    private volatile Instant retryAt = Instant.MIN;

    KafkaTripEventBus(KafkaTemplate<String, String> kafka, TripStreams streams, MeterRegistry metrics, Clock clock,
                      @Qualifier("tripKafkaSender") Executor sender, @Value("${APP_SERVER_NAME:}") String serverName) {
        this.sender = sender;
        this.consumerGroup = groupId(serverName, () -> InetAddress.getLocalHost().getHostName());
        log.info("여행 이벤트 Kafka 수신 그룹 {}", consumerGroup);
        this.kafka = kafka;
        this.streams = streams;
        this.metrics = metrics;
        this.clock = clock;
    }

    static String groupId(String configured, Callable<String> hostName) {
        String server = configured.strip();
        if (server.isEmpty()) {
            try {
                server = hostName.call();
            } catch (Exception unavailable) {
                server = "trova-" + UUID.randomUUID();
            }
        }
        return "trova-trip-events-" + server + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String getConsumerGroup() { return consumerGroup; }

    @Bean
    NewTopic tripEventsTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(replicas).build();
    }

    public void publish(TripChangedEvent event) {
        // Local delivery is independent of broker latency. Ignore our own Kafka echo.
        try {
            streams.deliver(event);
        } catch (RuntimeException failure) {
            metrics.counter("trova.trip-events.local-delivery-failures").increment();
            log.warn("Local delivery failed for trip {}", event.tripId(), failure);
        }
        if (clock.instant().isBefore(retryAt)) {
            metrics.counter("trova.trip-events.fallback").increment();
            return;
        }
        try {
            sender.execute(() -> send(event));
        } catch (RejectedExecutionException full) {
            metrics.counter("trova.trip-events.fallback").increment();
        }
    }

    private void send(TripChangedEvent event) {
        // Only the dedicated worker accesses Kafka. Recheck after waiting behind another send.
        if (clock.instant().isBefore(retryAt)) {
            metrics.counter("trova.trip-events.fallback").increment();
            return;
        }
        try {
            kafka.send(TOPIC, Long.toString(event.tripId()), json.writeValueAsString(new Envelope(origin, event)))
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            retryAt = clock.instant().plusSeconds(60);
            metrics.counter("trova.trip-events.fallback").increment();
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        // Reconnecting clients fetch the current trip. Skip records preceding assignment.
        callback.seekToEnd(assignments.keySet());
    }

    @KafkaListener(topics = TOPIC, groupId = "#{__listener.consumerGroup}",
                   properties = "auto.offset.reset=latest")
    void receive(String payload) throws java.io.IOException {
        Envelope message = json.readValue(payload, Envelope.class);
        if (!origin.equals(message.origin())) streams.deliver(message.event());
    }
}
