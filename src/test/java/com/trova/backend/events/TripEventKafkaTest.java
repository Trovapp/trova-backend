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
    AnnotationConfigApplicationContext node(EmbeddedKafkaBroker broker, String server) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", Map.of("app.trip-events.type", "kafka", "APP_SERVER_NAME", server)));
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
                ctx.getBean(TripStreams.class), new SimpleMeterRegistry(), Clock.systemUTC(), Runnable::run, server));

        ctx.register(Listeners.class);
        ctx.refresh();
        ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()
                .forEach(c -> ContainerTestUtils.waitForAssignment(c, 1));
        return ctx;
    }

    private String group(AnnotationConfigApplicationContext node) {
        return node.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().iterator().next().getGroupId();
    }

    @Test void 호스트명을_얻지_못해도_대체_그룹_이름을_만든다() {
        String group = KafkaTripEventBus.groupId("", () -> { throw new java.net.UnknownHostException(); });
        org.assertj.core.api.Assertions.assertThat(group).matches("trova-trip-events-trova-[a-f0-9-]{36}-[a-f0-9]{8}");
    }

    @Test void 재시작한_서버는_연결이_없던_동안의_변경을_재생하지_않는다(EmbeddedKafkaBroker broker) {
        try (var sender = node(broker, "publisher")) {
            var first = new TripChangedEvent(20, 1, 2, "친구", "TRIP_TITLE", "제목");
            try (var receiver = node(broker, "restarting")) {
                sender.getBean(KafkaTripEventBus.class).publish(first);
                verify(receiver.getBean(TripStreams.class), timeout(10000)).deliver(first);
            }
            var missed = new TripChangedEvent(20, 2, 2, "친구", "TRIP_TITLE", "부재 중");
            sender.getBean(KafkaTripEventBus.class).publish(missed);
            try (var restarted = node(broker, "restarting")) {
                var current = new TripChangedEvent(20, 3, 2, "친구", "TRIP_TITLE", "재시작 후");
                sender.getBean(KafkaTripEventBus.class).publish(current);
                verify(restarted.getBean(TripStreams.class), timeout(10000)).deliver(current);
                verify(restarted.getBean(TripStreams.class), never()).deliver(missed);
            }
        }
    }

    @Test void otherServersReceiveEachEvent(EmbeddedKafkaBroker broker) {
        try (var sender = node(broker, "sender"); var second = node(broker, "shared"); var third = node(broker, "shared")) {
            org.assertj.core.api.Assertions.assertThat(group(second)).startsWith("trova-trip-events-shared-")
                    .isNotEqualTo(group(third));
            var event = new TripChangedEvent(17, 3, 4, "민수", "PLACE_ADDED", "추가했어요");
            sender.getBean(KafkaTripEventBus.class).publish(event);
            verify(second.getBean(TripStreams.class), timeout(10000)).deliver(event);
            verify(third.getBean(TripStreams.class), timeout(10000)).deliver(event);
            verify(sender.getBean(TripStreams.class)).deliver(event);
            var deletion = new TripChangedEvent(17, 4, 4, "민수", "TRIP_DELETED", "삭제", java.util.List.of(4L, 5L));
            sender.getBean(KafkaTripEventBus.class).publish(deletion);
            verify(second.getBean(TripStreams.class), timeout(10000)).deliver(deletion);
            verify(third.getBean(TripStreams.class), timeout(10000)).deliver(deletion);
        }
    }
}
