package com.trova.backend.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.pipeline.ReviewSummaryRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(15)
class PlaceReviewConcurrentTest {
    private final PlaceRepository places = mock(PlaceRepository.class);
    private final GooglePlacesApiClient client = mock(GooglePlacesApiClient.class);
    private final ReviewSummaryRunner runner = mock(ReviewSummaryRunner.class);
    private final PlaceReviewService service = new PlaceReviewService(places, client, runner, mock(ApiCallLogService.class));
    private final AtomicReference<Place> stored = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ReviewSummary summary = new ReviewSummary("요약", List.of(), List.of(), null, null, List.of(), List.of());

    private record Outcome(Optional<PlaceReviewService.PlaceReviewInfo> value, Throwable error, boolean interrupted) { }

    @BeforeEach
    void 준비() {
        when(places.findById(1L)).thenAnswer(call -> Optional.of(stored.get() == null ? place("g1") : stored.get()));
        when(places.save(any())).thenAnswer(call -> { Place p = call.getArgument(0); stored.set(p); return p; });
        when(runner.run(any(), anyLong())).thenReturn(summary);
    }

    private Place place(String id) {
        return new Place(id, "장소", "cafe", 4.0, 10, null, 37.0, 127.0, null);
    }

    private GooglePlacesDetailsResponse details() {
        return new GooglePlacesDetailsResponse("g1", List.of(
                new GooglePlacesDetailsResponse.Review(new GooglePlacesDetailsResponse.ReviewText("좋아요"))), "02-111-2222");
    }

    private void gate() throws InterruptedException {
        entered.countDown();
        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private Outcome call(Long id) {
        try { return new Outcome(service.getOrGenerateSummary(id), null, Thread.currentThread().isInterrupted()); }
        catch (RuntimeException | Error error) { return new Outcome(null, error, Thread.currentThread().isInterrupted()); }
    }

    private void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Thread value = thread.get();
            if (value != null && (value.getState() == Thread.State.WAITING || value.getState() == Thread.State.TIMED_WAITING)) return;
            Thread.sleep(5);
        }
        throw new AssertionError("동시 요청이 대기 상태에 진입하지 않음");
    }

    private List<Outcome> pair(Runnable beforeRelease) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<Outcome> owner = pool.submit(() -> call(1L));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                AtomicReference<Thread> followerThread = new AtomicReference<>();
                Future<Outcome> follower = pool.submit(() -> { followerThread.set(Thread.currentThread()); return call(1L); });
                awaitWaiting(followerThread);
                assertThat(follower.isDone()).isFalse();
                beforeRelease.run();
                release.countDown();
                return List.of(owner.get(5, TimeUnit.SECONDS), follower.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test
    void 동시_캐시_미스는_상세와_요약과_저장을_한번만_실행한다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); return details(); });
        List<Outcome> results = pair(() -> verify(client, times(1)).getDetails("g1"));
        assertThat(results).allSatisfy(result -> { assertThat(result.error()).isNull(); assertThat(result.value().orElseThrow().summary()).isEqualTo(summary); });
        verify(runner, times(1)).run(any(), anyLong());
        verify(places, times(1)).save(any());
        assertThat(service.getOrGenerateSummary(1L).orElseThrow().phone()).isEqualTo("02-111-2222");
        verify(client, times(1)).getDetails("g1");
    }

    @Test
    void 상세_실패도_동시_요청에_공유하고_다음_독립_요청은_재시도한다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); throw new IllegalStateException("fixture failure"); });
        List<Outcome> results = pair(() -> verify(client, times(1)).getDetails("g1"));
        assertThat(results).allSatisfy(result -> assertThat(result.value().orElseThrow().summary().highlights()).isEqualTo("리뷰를 불러오지 못했어요."));
        verify(places, never()).save(any());
        doReturn(new GooglePlacesDetailsResponse("g1", List.of())).when(client).getDetails("g1");
        assertThat(service.getOrGenerateSummary(1L).orElseThrow().summary().highlights()).isEqualTo("리뷰 정보가 없어요.");
        verify(client, times(2)).getDetails("g1");
    }

    @Test
    void 요약_실패를_공유하고_영구_실패_캐시를_남기지_않는다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); return details(); });
        when(runner.run(any(), anyLong())).thenThrow(new IllegalStateException("summary failure"));
        List<Outcome> results = pair(() -> verify(client, times(1)).getDetails("g1"));
        assertThat(results).allSatisfy(result -> assertThat(result.value().orElseThrow().summary().highlights()).isEqualTo("리뷰를 불러오지 못했어요."));
        verify(runner, times(1)).run(any(), anyLong());
        verify(places, never()).save(any());
        doReturn(summary).when(runner).run(any(), anyLong());
        assertThat(service.getOrGenerateSummary(1L).orElseThrow().summary()).isEqualTo(summary);
        verify(client, times(2)).getDetails("g1");
    }

    @Test
    void 저장_예외를_양쪽에_전달하고_다음_요청의_진행을_막지_않는다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); return details(); });
        IllegalStateException failure = new IllegalStateException("save failure");
        when(places.save(any())).thenThrow(failure);
        List<Outcome> results = pair(() -> verify(client, times(1)).getDetails("g1"));
        assertThat(results).allSatisfy(result -> assertThat(result.error()).isSameAs(failure));
        doAnswer(call -> call.getArgument(0)).when(places).save(any());
        assertThat(service.getOrGenerateSummary(1L).orElseThrow().summary()).isEqualTo(summary);
        verify(client, times(2)).getDetails("g1");
    }

    @Test
    void 없는_장소도_공유하고_이후_생긴_장소를_다시_조회한다() throws Exception {
        when(places.findById(1L)).thenAnswer(call -> { gate(); return Optional.empty(); });
        List<Outcome> results = pair(() -> verify(places, times(1)).findById(1L));
        assertThat(results).allSatisfy(result -> assertThat(result.value()).isEmpty());
        when(places.findById(1L)).thenReturn(Optional.of(place("g1")));
        doReturn(new GooglePlacesDetailsResponse("g1", List.of())).when(client).getDetails("g1");
        assertThat(service.getOrGenerateSummary(1L)).isPresent();
    }

    @Test
    void 다른_장소는_진행중인_상세_조회를_기다리지_않는다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); return details(); });
        when(places.findById(2L)).thenReturn(Optional.of(place("g2")));
        when(client.getDetails("g2")).thenReturn(new GooglePlacesDetailsResponse("g2", List.of()));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<Outcome> owner = pool.submit(() -> call(1L));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Outcome other = pool.submit(() -> call(2L)).get(2, TimeUnit.SECONDS);
                assertThat(other.error()).isNull();
                assertThat(other.value()).isPresent();
                assertThat(owner.isDone()).isFalse();
            } finally { release.countDown(); }
        }
    }

    @Test
    void 캐시된_요약의_전화번호_보충도_동시에_한번만_호출한다() throws Exception {
        Place cached = place("g1");
        cached.applyReviewSummary(new ObjectMapper().writeValueAsString(summary));
        stored.set(cached);
        when(client.getPhone("g1")).thenAnswer(call -> { gate(); return new GooglePlacesDetailsResponse("g1", List.of(), "02-123"); });
        List<Outcome> results = pair(() -> verify(client, times(1)).getPhone("g1"));
        assertThat(results).allSatisfy(result -> assertThat(result.value().orElseThrow().phone()).isEqualTo("02-123"));
        verify(client, never()).getDetails(any());
        verify(runner, never()).run(any(), anyLong());
        service.getOrGenerateSummary(1L);
        verify(client, times(1)).getPhone("g1");
    }

    @Test
    void 대기자_인터럽트는_플래그를_보존하며_공유_생성을_취소하지_않는다() throws Exception {
        when(client.getDetails("g1")).thenAnswer(call -> { gate(); return details(); });
        AtomicReference<Outcome> followerResult = new AtomicReference<>();
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        Thread follower = new Thread(() -> { followerThread.set(Thread.currentThread()); followerResult.set(call(1L)); });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            try {
                Future<Outcome> owner = pool.submit(() -> call(1L));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                follower.start(); awaitWaiting(followerThread); follower.interrupt(); follower.join(2000);
                assertThat(follower.isAlive()).isFalse();
                assertThat(followerResult.get().interrupted()).isTrue();
                assertThat(followerResult.get().error()).isInstanceOf(IllegalStateException.class);
                assertThat(owner.isDone()).isFalse();
                verify(client, times(1)).getDetails("g1");
                release.countDown();
                assertThat(owner.get(5, TimeUnit.SECONDS).value().orElseThrow().summary()).isEqualTo(summary);
            } finally { release.countDown(); follower.join(2000); }
        }
    }
}
