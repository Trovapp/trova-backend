package com.trova.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class UserListReadsTest {
    @Test
    void 오래_걸린_조회가_쓰기_커밋_이후_캐시를_덮지_않는다() throws Exception {
        UserListReads reads = new UserListReads(2);
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
                reads.afterCommit(mock(TransactionExecution.class), null);
                assertThat(reads.trips(1L, () -> List.of(trip()))).hasSize(1);
            } finally {
                resume.countDown();
            }
            assertThat(old.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(reads.trips(1L, List::of)).hasSize(1);
        }
    }

    @Test
    void 포화된_미스는_429이고_히트와_실패_후_재시도는_가능하다() throws Exception {
        UserListReads reads = new UserListReads(1);
        reads.places(1L, List::of);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<TripController.TripResponse>> slow = executor.submit(() -> reads.trips(2L, () -> {
                started.countDown();
                await(resume);
                throw new IllegalStateException("load failed");
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(reads.places(1L, List::of)).isEmpty();
                assertThatThrownBy(() -> reads.bookmarks(3L, List::of))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
            } finally {
                resume.countDown();
            }
            assertThatThrownBy(() -> slow.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(reads.trips(2L, () -> List.of(trip()))).hasSize(1);
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
