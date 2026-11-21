package com.trova.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.trip-events.type", havingValue = "kafka")
class KafkaTripEventBus implements TripEventBus {
    static final String TOPIC = "trova.trip-events";
    record Envelope(String origin, TripChangedEvent event) {}
    private final String origin = UUID.randomUUID().toString();
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json = new ObjectMapper();
    private final TripStreams streams;
    private final MeterRegistry metrics;
    private final Clock clock;
    private volatile Instant retryAt = Instant.MIN;
    KafkaTripEventBus(KafkaTemplate<String, String> kafka, TripStreams streams,
                      MeterRegistry metrics, Clock clock) {
        this.kafka = kafka;
        this.streams = streams;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Bean
    NewTopic tripEventsTopic(@Value("${app.queue.kafka.replicas:1}") int replicas) {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(replicas).build();
    }

    public void publish(TripChangedEvent event) {
        // Local delivery is independent of broker latency. Ignore our own Kafka echo.
        streams.deliver(event);
        if (!clock.instant().isBefore(retryAt)) {
            try {
                kafka.send(TOPIC, Long.toString(event.tripId()), json.writeValueAsString(new Envelope(origin, event)))
                        .get(2, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                retryAt = clock.instant().plusSeconds(60);
            }
        }
        metrics.counter("trova.trip-events.fallback").increment();
    }

    @KafkaListener(topics = TOPIC, groupId = "trova-trip-events-#{T(java.util.UUID).randomUUID()}",
                   properties = "auto.offset.reset=latest")
    void receive(String payload) throws java.io.IOException {
        Envelope message = json.readValue(payload, Envelope.class);
        if (!origin.equals(message.origin())) streams.deliver(message.event());
    }
}
