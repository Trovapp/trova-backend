package com.trova.backend.events;

import com.trova.backend.push.ExpoPushService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.trova.backend.push.PushNotificationService;
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
    @Test void JWT가_만료되면_열려_있던_연결을_닫고_다시_접속할_수_있다() {
        try (var mocks = mockConstruction(SseEmitter.class)) {
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setAttribute("trova.jwt.expiresAt", now.plusSeconds(1));
            var users = mock(com.trova.backend.service.CurrentUserService.class);
            var auth = mock(org.springframework.security.core.Authentication.class);
            when(users.resolveUserId(auth)).thenReturn(5L);
            when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(0, List.of(5L)));
            var controller = new TripEventsController(streams, members, null, users, clock);
            SseEmitter emitter = controller.open(7, null, auth, request).getBody();
            ((TripStream) request.getAttribute(TripSseMvc.STREAM)).ready(null);
            assertThat(streams.viewing(7, 5)).isTrue();
            when(clock.instant()).thenReturn(now.plusSeconds(1));
            streams.heartbeat();
            verify(emitter, timeout(1000)).complete();
            org.awaitility.Awaitility.await().untilAsserted(() ->
                    assertThatCode(() -> open(7, 5, now.plusSeconds(10))).doesNotThrowAnyException());
        }
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

    @Test void 브로커가_응답하지_않아도_다른_발행은_로컬에_즉시_전달된다() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException();
        });
        TripStreams local = mock(TripStreams.class);
        KafkaTripEventBus bus = new KafkaTripEventBus(kafka, local, new SimpleMeterRegistry(), clock);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> bus.publish(event));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                pool.submit(() -> bus.publish(event)).get(1, TimeUnit.SECONDS);
                verify(local, times(2)).deliver(event);
            } finally {
                release.countDown();
            }
            first.get(2, TimeUnit.SECONDS);
            verify(kafka).send(anyString(), anyString(), anyString());
        }
    }

    @Test void 동시에_도착한_변경도_브로커가_정상이면_모두_발송한다() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CountDownLatch started = new CountDownLatch(1);
        var firstSend = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            return firstSend;
        });
        KafkaTripEventBus bus = new KafkaTripEventBus(kafka, mock(TripStreams.class), new SimpleMeterRegistry(), clock);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> bus.publish(event));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                bus.publish(new TripChangedEvent(8, 1, 4, "민수", "TRIP_TITLE", "변경"));
            } finally {
                firstSend.complete(null);
            }
            first.get(2, TimeUnit.SECONDS);
            verify(kafka).send(anyString(), eq("7"), anyString());
            verify(kafka).send(anyString(), eq("8"), anyString());
        }
    }

    @Test void 가입_확인이_느려도_기존_연결에_전달한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(3, List.of(5L)));
        when(members.version(8, 6)).thenAnswer(call -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 0L;
        });
        try (var mocks = mockConstruction(SseEmitter.class); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            SseEmitter current = open(7, 5, now.plusSeconds(60));
            pool.submit(() -> open(8, 6, now.plusSeconds(60)));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                pool.submit(() -> streams.deliver(event)).get(1, TimeUnit.SECONDS);
                verify(current, timeout(1000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
            } finally {
                release.countDown();
            }
        }
    }

    @Test void 푸시에_실패하면_다음_변경은_바로_알린다() {
        ExpoPushService expo = mock(ExpoPushService.class);
        when(members.members(7)).thenReturn(List.of(6L));
        when(expo.sendToUser(eq(6L), eq("trip-change"), any()))
                .thenReturn(new PushNotificationService.Result(0, 0, 1), new PushNotificationService.Result(1, 0, 0));
        TripChangePush push = new TripChangePush(members, streams, expo, clock, Runnable::run, new SimpleMeterRegistry());
        push.changed(event);
        push.changed(event);
        push.changed(event);
        verify(expo, times(2)).sendToUser(eq(6L), eq("trip-change"), any());
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

    @Test void 푸시_대기열이_차면_일반_알림은_집계하고_삭제는_발송한다() throws Exception {
        ExpoPushService expo = mock(ExpoPushService.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        var config = new com.trova.backend.config.AsyncConfig();
        var executor = (org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor) config.tripPushExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            int capacity = executor.getThreadPoolExecutor().getQueue().remainingCapacity();
            for (int i = 0; i < capacity; i++) executor.execute(() -> {});
            TripChangePush push = new TripChangePush(members, streams, expo, clock, executor, metrics);
            push.changed(event);
            push.changed(new TripChangedEvent(7, 4, 4, "민수", "TRIP_DELETED", "삭제", List.of(4L, 6L)));
            assertThat(metrics.get("trova.trip-push.discarded").counter().count()).isOne();
            verify(expo).sendToUser(eq(6L), eq("trip-deleted"), any());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test void 다른_서버의_추방_이벤트는_해당_사용자의_연결을_닫는다() throws Exception {
        try (var mocks = mockConstruction(SseEmitter.class)) {
            SseEmitter removed = open(7, 5, now.plusSeconds(60));
            open(7, 6, now.plusSeconds(60));
            when(members.snapshot(7)).thenReturn(new TripAudience.Snapshot(3, List.of(6L)));
            KafkaTripEventBus bus = new KafkaTripEventBus(mock(KafkaTemplate.class), streams, new SimpleMeterRegistry(), clock);
            bus.receive(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    new KafkaTripEventBus.Envelope("remote", event)));
            verify(removed, timeout(1000)).complete();
            assertThat(streams.viewing(7, 6)).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void 연결이_끝나거나_시간이_지나면_사용자_접속_자리를_돌려준다(boolean timedOut) {
        try (var mocks = mockConstruction(SseEmitter.class)) {
            SseEmitter emitter = open(7, 5, now.plusSeconds(60));
            var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            if (timedOut) verify(emitter).onTimeout(callback.capture());
            else verify(emitter).onCompletion(callback.capture());
            callback.getValue().run();
            org.awaitility.Awaitility.await().untilAsserted(() ->
                    assertThatCode(() -> open(7, 5, now.plusSeconds(60))).doesNotThrowAnyException());
        }
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
        when(expo.sendToUser(anyLong(), anyString(), any())).thenReturn(new PushNotificationService.Result(1, 0, 0));
        open(7, 5, now.plusSeconds(600));
        when(members.members(7)).thenReturn(List.of(4L, 5L, 6L));
        var push = new TripChangePush(members, streams, expo, clock, Runnable::run, new SimpleMeterRegistry());
        push.changed(event);
        push.changed(event);
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        push.changed(event);
        verify(expo, times(2)).sendToUser(eq(6L), eq("trip-change"), any());
        verifyNoMoreInteractions(expo);
    }
}
