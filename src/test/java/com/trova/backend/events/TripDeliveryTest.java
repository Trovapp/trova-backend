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

    @org.junit.jupiter.api.AfterEach void stopWriters() { streams.shutdown(); }

    SseEmitter open(long trip, long user, Instant end) {
        var stream = streams.open(trip, user, new StreamAuth(end, null));
        stream.ready(null);
        return stream.emitter;
    }

    @Test void memberReceivesReadyHeartbeatAndChangesUntilRemoval() throws Exception {
        try (var emitters = mockConstruction(SseEmitter.class)) {
            var present = new TripAudience.Snapshot(0, List.of(5L));
            when(members.snapshot(7)).thenReturn(present, present, new TripAudience.Snapshot(1, List.of()));
            var stream = open(7, 5, now.plusSeconds(100));
            streams.heartbeat();
            var bus = new MemoryTripEventBus(streams);
            bus.publish(event);
            verify(stream, timeout(1000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
            bus.publish(event);
            verify(stream, timeout(1000)).complete();
            assertThat(streams.viewing(7, 5)).isFalse();
        }
    }

    @Test void rejectsNonmemberAndLimitsConnections() {
        when(members.version(7, 6)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> open(7, 6, now.plusSeconds(10))).isInstanceOf(ResponseStatusException.class);
        open(7, 5, now.plusSeconds(10));
        assertThatThrownBy(() -> open(7, 5, now.plusSeconds(10))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void expiredCredentialsCloseStream() {
        when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(0, List.of(5L)));
        open(7, 5, now.plusSeconds(1));
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        streams.heartbeat();
        assertThat(streams.viewing(7, 5)).isFalse();
        org.awaitility.Awaitility.await().untilAsserted(() ->
                assertThatCode(() -> open(7, 5, now.plusSeconds(10))).doesNotThrowAnyException());
    }

    @Test void missedVersionReconnectsOnlyStaleSubscriberAfterGraceInterval() throws Exception {
        try (var emitters = mockConstruction(SseEmitter.class)) {
            when(members.version(7, 5)).thenReturn(2L);
            when(members.version(7, 6)).thenReturn(3L);
            var stale = open(7, 5, now.plusSeconds(100));
            var current = open(7, 6, now.plusSeconds(100));
            when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(3, List.of(5L, 6L)));
            streams.heartbeat();
            assertThat(streams.viewing(7, 5)).isTrue();
            streams.heartbeat();
            verify(stale, timeout(1000)).complete();
            verify(current, never()).complete();
            assertThat(streams.viewing(7, 6)).isTrue();
        }
    }

    @Test void deletionOnAnotherNodeOnlyReachesFinalMembers() throws Exception {
        try (var emitters = mockConstruction(SseEmitter.class)) {
            var member = open(7, 5, now.plusSeconds(100));
            var removed = open(7, 6, now.plusSeconds(100));
            verify(member, timeout(1000)).send(any(SseEmitter.SseEventBuilder.class));
            verify(removed, timeout(1000)).send(any(SseEmitter.SseEventBuilder.class));
            when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(-1, List.of()));
            streams.deliver(event);
            streams.heartbeat();
            var deletion = new TripChangedEvent(7, 4, 4, "민수", "TRIP_DELETED", "삭제", List.of(4L, 5L));
            streams.deliver(deletion);
            streams.deliver(deletion);
            verify(member, timeout(1000)).complete();
            verify(member, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(removed, timeout(1000)).complete();
            verify(removed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        }
    }

    @Test void slowClientDoesNotHoldPublisherOrHealthyConnection() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(0, List.of(5L, 6L)));
        try (var mocks = mockConstruction(SseEmitter.class, (mock, context) -> {
            if (context.getCount() == 1) doAnswer(invocation -> {
                entered.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            }).when(mock).send(any(SseEmitter.SseEventBuilder.class));
        })) {
            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(1), () -> open(7, 5, now.plusSeconds(100)));
            assertThat(entered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var fast = open(7, 6, now.plusSeconds(100));
            for (int i = 0; i < 20; i++) {
                streams.deliver(event);
                verify(fast, timeout(1000).times(i + 2)).send(any(SseEmitter.SseEventBuilder.class));
            }
            assertThat(streams.viewing(7, 5)).isFalse();
            assertThat(streams.viewing(7, 6)).isTrue();
            verify(mocks.constructed().getFirst(), timeout(1000)).complete();
        } finally { release.countDown(); }
    }

    @Test void localFailureStillPublishesToKafka() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var local = mock(TripStreams.class);
        doThrow(new IllegalStateException("local delivery")).when(local).deliver(event);
        var bus = new KafkaTripEventBus(kafka, local, new SimpleMeterRegistry(), clock);
        bus.publish(event);
        verify(kafka).send(eq(KafkaTripEventBus.TOPIC), eq("7"), anyString());
    }

    @Test void eventExecutorOverloadRetriesLatestVersionForEachTrip() {
        var bus = mock(TripEventBus.class);
        var submitted = new java.util.ArrayDeque<Runnable>();
        var rejecting = new java.util.concurrent.atomic.AtomicBoolean(true);
        var listener = new TripChangeListener(bus, job -> {
            if (rejecting.get()) throw new java.util.concurrent.RejectedExecutionException();
            submitted.add(job);
        }, clock, new SimpleMeterRegistry());
        var older = new TripChangedEvent(7, 2, 4, "민수", "TRIP_TITLE", "제목");
        var other = new TripChangedEvent(8, 1, 4, "민수", "TRIP_TITLE", "제목");
        listener.changed(event);
        listener.changed(older);
        listener.changed(other);
        verifyNoInteractions(bus);
        rejecting.set(false);
        listener.retry();
        submitted.forEach(Runnable::run);
        verify(bus).publish(event);
        verify(bus).publish(other);
        verifyNoMoreInteractions(bus);
    }

    @Test void editPushHasAnIndependentExecutor() throws Exception {
        var async = TripChangePush.class.getMethod("changed", TripChangedEvent.class)
                .getAnnotation(org.springframework.scheduling.annotation.Async.class);
        assertThat(async.value()).isEqualTo("tripPushExecutor");
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
        open(7, 5, now.plusSeconds(600));
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
