package com.trova.backend.controller;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserListReadsTest {
    @Test
    void 오래_걸린_조회가_쓰기_커밋_이후_캐시를_덮지_않는다() throws Exception {
        UserListReads reads = new UserListReads();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<TripController.TripResponse>> old = executor.submit(() -> reads.trips(1L, () -> {
                started.countDown();
                await(resume);
                return List.of();
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                reads.invalidateTrips(Set.of(1L), false);
                assertThat(reads.trips(1L, () -> List.of(trip()))).hasSize(1);
            } finally {
                resume.countDown();
            }
            assertThat(old.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(reads.trips(1L, List::of)).hasSize(1);
        }
    }

    @Test
    void 같은_사용자_40건이_한_로더를_기다려도_다른_사용자는_즉시_조회한다() throws Exception {
        UserListReads reads = new UserListReads();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch callers = new CountDownLatch(40);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<TripController.TripResponse>>> waiting = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                waiting.add(executor.submit(() -> {
                    callers.countDown();
                    return reads.trips(2L, () -> {
                        loads.incrementAndGet();
                        started.countDown();
                        await(resume);
                        return List.of(trip());
                    });
                }));
            }
            try {
                await(started);
                await(callers);
                assertThat(reads.trips(3L, List::of)).isEmpty();
                assertThat(waiting).noneMatch(Future::isDone);
            } finally {
                resume.countDown();
            }
            for (Future<List<TripController.TripResponse>> result : waiting) {
                assertThat(result.get(5, TimeUnit.SECONDS)).containsExactly(trip());
            }
            assertThat(loads.get()).isOne();
        }
    }

    @Test
    void 실패한_조회는_다음_요청이_다시_시도한다() {
        UserListReads reads = new UserListReads();
        assertThatThrownBy(() -> reads.trips(1L, () -> { throw new IllegalStateException("load failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reads.trips(1L, () -> List.of(trip()))).containsExactly(trip());
    }

    @Test
    void 같은_해시의_다른_사용자는_느린_로더가_끝나기_전에_조회한다() throws Exception {
        UserListReads reads = new UserListReads();
        long collision = 1L << 32;
        assertThat(Long.hashCode(collision)).isEqualTo(Long.hashCode(1L));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> reads.trips(1L, () -> {
                started.countDown();
                await(resume);
                return List.of();
            }));
            try {
                await(started);
                Future<?> second = executor.submit(() -> reads.trips(collision, List::of));
                second.get(2, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 멈춘_로더를_기다리던_요청은_503으로_끝나고_새_조회가_캐시에_남는다() throws Exception {
        UserListReads reads = new UserListReads();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> loader = executor.submit(() -> reads.trips(1L, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return List.of();
            }));
            try {
                await(started);
                Future<?> waiter = executor.submit(() -> reads.trips(1L, () -> {
                    throw new AssertionError("unexpected second load");
                }));
                assertThatThrownBy(() -> waiter.get(6, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).cause()
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                assertThat(reads.trips(1L, () -> List.of(trip()))).containsExactly(trip());
            } finally {
                release.countDown();
            }
            loader.get(5, TimeUnit.SECONDS);
            assertThat(reads.trips(1L, List::of)).containsExactly(trip());
        }
    }

    @Test
    void 같은_키에서_이미_대기하는_요청에도_로더의_원래_예외를_전달한다() throws Exception {
        UserListReads reads = new UserListReads();
        IllegalStateException failure = new IllegalStateException("load failed");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> loader = executor.submit(() -> reads.trips(1L, () -> {
                started.countDown();
                await(release);
                throw failure;
            }));
            await(started);
            FutureTask<?> waiter = new FutureTask<>(() -> reads.trips(1L, () -> {
                throw new AssertionError("unexpected second load");
            }));
            Thread thread = Thread.startVirtualThread(waiter);
            try {
                Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() ->
                        thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
            } finally {
                release.countDown();
            }
            for (Future<?> request : List.of(loader, waiter)) {
                assertThatThrownBy(() -> request.get(5, TimeUnit.SECONDS)).cause().isSameAs(failure);
            }
        }
    }

    private static TripController.TripResponse trip() {
        return new TripController.TripResponse(1L, "Trip", null, null, 0, List.of(), "OWNER", 0, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
