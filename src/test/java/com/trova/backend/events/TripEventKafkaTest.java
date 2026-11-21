package com.trova.backend.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import static org.mockito.Mockito.*;

@EmbeddedKafka(topics = KafkaTripEventBus.TOPIC, partitions = 1)
class TripEventKafkaTest {
    @EnableKafka static class Listeners {}
    AnnotationConfigApplicationContext node(EmbeddedKafkaBroker broker) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", Map.of("app.trip-events.type", "kafka")));
        var brokers = broker.getBrokersAsString();
        var producers = new DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers", brokers,
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class));
        var factory = new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(Map.of("bootstrap.servers", brokers,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class)));
        ctx.registerBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class, () -> factory);
        ctx.registerBean(DefaultKafkaProducerFactory.class, () -> producers);
        ctx.registerBean("streams", TripStreams.class, () -> mock(TripStreams.class));
        ctx.registerBean(KafkaTripEventBus.class, () -> new KafkaTripEventBus(new KafkaTemplate<>(producers),
                ctx.getBean(TripStreams.class), new SimpleMeterRegistry(), Clock.systemUTC()));

        ctx.register(Listeners.class);
        ctx.refresh();
        ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()
                .forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));
        return ctx;
    }

    @Test void otherServersReceiveEachEvent(EmbeddedKafkaBroker broker) {
        try (var sender = node(broker); var second = node(broker); var third = node(broker)) {
            var event = new TripChangedEvent(17, 3, 4, "민수", "PLACE_ADDED", "추가했어요");
            sender.getBean(KafkaTripEventBus.class).publish(event);
            verify(second.getBean(TripStreams.class), timeout(10000)).deliver(event);
            verify(third.getBean(TripStreams.class), timeout(10000)).deliver(event);
            verify(sender.getBean(TripStreams.class)).deliver(event);
        }
    }
}
