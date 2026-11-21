package com.trova.backend.events;

import com.trova.backend.push.ExpoPushService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripDeliveryTest {
    final Clock clock = mock(Clock.class);
    final Instant now = Instant.EPOCH;
    final TripAudience members = mock(TripAudience.class);
    final TripStreams streams = new TripStreams(members, clock, 1, 4);
    final TripChangedEvent event = new TripChangedEvent(7, 3, 4, "민수", "MEMBER_REMOVED", "변경됐어요");
    TripDeliveryTest() { when(clock.instant()).thenReturn(now); }

    @Test void memberReceivesReadyHeartbeatAndChangesUntilRemoval() throws Exception {
        try (var emitters = mockConstruction(SseEmitter.class)) {
            when(members.members(7)).thenReturn(List.of(5L), List.of(5L), List.of());
            var stream = streams.open(7, 5, now.plusSeconds(100));
            streams.heartbeat();
            var bus = new MemoryTripEventBus(streams);
            bus.publish(event);
            bus.publish(event);
            verify(stream, times(3)).send(any(SseEmitter.SseEventBuilder.class));
            verify(stream).complete();
            assertThat(streams.viewing(7, 5)).isFalse();
        }
    }

    @Test void rejectsNonmemberAndLimitsConnections() {
        when(members.version(7, 6)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> streams.open(7, 6, now.plusSeconds(10))).isInstanceOf(ResponseStatusException.class);
        streams.open(7, 5, now.plusSeconds(10));
        assertThatThrownBy(() -> streams.open(7, 5, now.plusSeconds(10))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void expiredCredentialsCloseStream() {
        when(members.members(7)).thenReturn(List.of(5L));
        streams.open(7, 5, now.plusSeconds(1));
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        streams.heartbeat();
        assertThat(streams.viewing(7, 5)).isFalse();
        streams.open(7, 5, now.plusSeconds(10));
    }

    @Test void fallbackSkipsBrokerForOneMinute() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
        var metrics = new SimpleMeterRegistry();
        var local = mock(TripStreams.class);
        var bus = new KafkaTripEventBus(kafka, local, metrics, clock);
        bus.publish(event);
        bus.publish(event);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        bus.publish(event);
        verify(kafka, times(2)).send(eq("trova.trip-events"), eq("7"), anyString());
        verify(local, times(3)).deliver(event);
        assertThat(metrics.counter("trova.trip-events.fallback").count()).isEqualTo(3);
    }

    @Test void pushOnlyOncePerFiveMinutesToOfflineMembers() {
        var expo = mock(ExpoPushService.class);
        streams.open(7, 5, now.plusSeconds(600));
        when(members.members(7)).thenReturn(List.of(4L, 5L, 6L));
        var push = new TripChangePush(members, streams, expo, clock);
        push.changed(event);
        push.changed(event);
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        push.changed(event);
        verify(expo, times(2)).sendToUser(eq(6L), eq("trip-change"), any());
        verifyNoMoreInteractions(expo);
    }
}
