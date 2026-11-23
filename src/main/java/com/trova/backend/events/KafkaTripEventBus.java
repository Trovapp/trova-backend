package com.trova.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
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
    // While a send is in flight, other publishers deliver locally and leave the broker work here.
    private static final int MAX_PENDING = 256;
    private final ArrayDeque<TripChangedEvent> pending = new ArrayDeque<>();
    private boolean sending;
    private Instant retryAt = Instant.MIN;
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
        try {
            streams.deliver(event);
        } catch (RuntimeException failure) {
            metrics.counter("trova.trip-events.local-delivery-failures").increment();
            log.warn("Local delivery failed for trip {}", event.tripId(), failure);
        }
        synchronized (pending) {
            if (clock.instant().isBefore(retryAt) || pending.size() >= MAX_PENDING) {
                metrics.counter("trova.trip-events.fallback").increment();
                return;
            }
            if (sending) {
                pending.addLast(event);
                return;
            }
            sending = true;
        }
        while (event != null) {
            try {
                kafka.send(TOPIC, Long.toString(event.tripId()), json.writeValueAsString(new Envelope(origin, event)))
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                synchronized (pending) {
                    retryAt = clock.instant().plusSeconds(60);
                    metrics.counter("trova.trip-events.fallback").increment(1 + pending.size());
                    pending.clear();
                    sending = false;
                }
                return;
            }
            synchronized (pending) {
                event = pending.pollFirst();
                sending = event != null;
            }
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        // Reconnecting clients fetch the current trip. A stable group may have old committed offsets.
        callback.seekToEnd(assignments.keySet());
    }

    @KafkaListener(topics = TOPIC, groupId = "trova-trip-events-${APP_SERVER_NAME:#{T(java.net.InetAddress).getLocalHost().getHostName()}}",
                   properties = "auto.offset.reset=latest")
    void receive(String payload) throws java.io.IOException {
        Envelope message = json.readValue(payload, Envelope.class);
        if (!origin.equals(message.origin())) streams.deliver(message.event());
    }
}
