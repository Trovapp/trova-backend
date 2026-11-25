package com.trova.backend.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class TripStreamSlotsTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final TripStreams streams = new TripStreams(mock(TripAudience.class), clock, 3, 256, metrics);
    private final StreamAuth auth = new StreamAuth(Instant.EPOCH.plusSeconds(3600), null);

    @AfterEach
    void 종료한다() { streams.shutdown(); }

    private double active() { return metrics.get("trova.trip-events.active").gauge().value(); }

    @Test
    void 네번째는_같은_사용자의_가장_오래된_다른_여행_연결을_교체한다() throws Exception {
        try (MockedConstruction<SseEmitter> mocks = mockConstruction(SseEmitter.class)) {
            TripStream first = streams.open(7, 5, auth);
            TripStream second = streams.open(8, 5, auth);
            TripStream third = streams.open(9, 5, auth);
            TripStream other = streams.open(7, 6, auth);
            TripStream fourth = streams.open(10, 5, auth);

            first.ready(null);
            ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(first.emitter, timeout(1000)).complete();
            verify(first.emitter, Mockito.atLeastOnce()).send(frames.capture());
            assertThat(frames.getAllValues().stream().flatMap(frame -> frame.build().stream())
                    .map(part -> part.getData().toString()).toList()).anyMatch(data -> data.contains("event:replaced"));
            assertThat(frames.getAllValues().getLast().build()).extracting(SseEmitter.DataWithMediaType::getData)
                    .contains(Map.of("reason", "connection_limit"));
            assertThat(first.active()).isFalse();
            assertThat(second.active()).isTrue();
            assertThat(third.active()).isTrue();
            assertThat(other.active()).isTrue();
            assertThat(fourth.active()).isTrue();
            assertThat(active()).isEqualTo(4);
            assertThat(metrics.get("trova.trip-events.replaced").counter().count()).isEqualTo(1);
            assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        }
    }

    @Test
    void 종료_이벤트를_기다리는_연결은_다시_교체하지_않는다() {
        TripStream first = streams.open(7, 5, auth);
        streams.open(8, 5, auth);
        streams.open(9, 5, auth);
        first.finish(new TripChangedEvent(7, 1, 5, "사용자", "TRIP_DELETED", "삭제"));
        streams.open(10, 5, auth);
        assertThat(metrics.get("trova.trip-events.replaced").counter().count()).isZero();
    }

    @Test
    void 반환한_슬롯의_writer가_계속_막혀도_무한히_추가하지_않는다() throws Exception {
        SimpleMeterRegistry limitedMetrics = new SimpleMeterRegistry();
        TripStreams limited = new TripStreams(mock(TripAudience.class), clock, 3, 1, limitedMetrics);
        Semaphore unblock = new Semaphore(0);
        Semaphore sending = new Semaphore(0);
        try (MockedConstruction<SseEmitter> mocks = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doAnswer(call -> { sending.release(); unblock.acquireUninterruptibly(); return null; })
                    .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        })) {
            for (int i = 0; i < 2; i++) {
                TripStream stream = limited.open(7, i, auth);
                stream.ready(null);
                assertThat(sending.tryAcquire(2, TimeUnit.SECONDS)).isTrue();
                stream.close();
            }
            assertThatThrownBy(() -> limited.open(7, 9, auth))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            assertThat(limitedMetrics.get("trova.trip-events.active").gauge().value()).isZero();
            assertThat(limitedMetrics.get("trova.trip-events.writers").gauge().value()).isEqualTo(2);
            unblock.release(2);
            await().untilAsserted(() ->
                    assertThat(limitedMetrics.get("trova.trip-events.writers").gauge().value()).isZero());
            assertThat(limited.open(7, 9, auth).active()).isTrue();
        } finally { unblock.release(2); limited.shutdown(); }
    }

    @Test
    void 송신_실행기_거절은_슬롯을_반환하고_원래_예외를_전달한다() {
        SimpleMeterRegistry rejectedMetrics = new SimpleMeterRegistry();
        TripStreams rejected = new TripStreams(mock(TripAudience.class), clock, 3, 1, rejectedMetrics);
        rejected.shutdown();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> rejected.open(7, 5, auth))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(rejectedMetrics.get("trova.trip-events.active").gauge().value()).isZero();
            assertThat(rejectedMetrics.get("trova.trip-events.writers").gauge().value()).isZero();
        }
    }

    @Test
    void 전체_256개에서는_새_사용자를_429로_막고_기존_사용자_교체는_허용한다() {
        for (int user = 0; user < 254; user++) streams.open(7, user, auth);
        streams.open(8, 0, auth);
        streams.open(9, 0, auth);
        assertThat(active()).isEqualTo(256);
        assertThatThrownBy(() -> streams.open(7, 999, auth))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(streams.open(10, 0, auth).active()).isTrue();
        assertThat(active()).isEqualTo(256);
    }

    @Test
    void 완료_오류_타임아웃은_송신기_종료를_기다리지_않고_한번만_반환한다() {
        AtomicReference<Runnable> completion = new AtomicReference<>();
        AtomicReference<Runnable> timedOut = new AtomicReference<>();
        AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
        try (MockedConstruction<SseEmitter> mocks = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doAnswer(call -> { completion.set(call.getArgument(0)); return null; }).when(emitter).onCompletion(any());
            doAnswer(call -> { timedOut.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
            doAnswer(call -> { error.set(call.getArgument(0)); return null; }).when(emitter).onError(any());
        })) {
            for (int callback = 0; callback < 3; callback++) {
                TripStream stream = streams.open(7, callback, auth);
                if (callback == 0) completion.get().run();
                if (callback == 1) timedOut.get().run();
                if (callback == 2) error.get().accept(new IOException("disconnected"));
                assertThat(active()).isZero();
                stream.close();
                assertThat(active()).isZero();
            }
        }
    }

    @Test
    void 쓰기_IOException은_emitter_완료가_막혀도_즉시_슬롯을_반환한다() throws Exception {
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        try (MockedConstruction<SseEmitter> mocks = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            doAnswer(call -> { completing.countDown(); unblock.await(5, TimeUnit.SECONDS); return null; })
                    .when(emitter).complete();
        })) {
            TripStream stream = streams.open(7, 5, auth);
            stream.ready(null);
            assertThat(completing.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(active()).isZero();
        } finally { unblock.countDown(); }
    }

    @Test
    void 송신이_인터럽트로_풀리지_않아도_close가_즉시_반환한다() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        Semaphore unblock = new Semaphore(0);
        try (MockedConstruction<SseEmitter> mocks = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doAnswer(call -> { sending.countDown(); unblock.acquireUninterruptibly(); return null; })
                    .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        })) {
            TripStream stream = streams.open(7, 5, auth);
            stream.ready(null);
            assertThat(sending.await(1, TimeUnit.SECONDS)).isTrue();
            stream.close();
            assertThat(active()).isZero();
            assertThat(streams.open(7, 6, auth).active()).isTrue();
            assertThat(active()).isEqualTo(1);
        } finally { unblock.release(); }
    }
}
