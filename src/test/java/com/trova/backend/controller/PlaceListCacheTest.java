package com.trova.backend.controller;

import com.trova.backend.entity.SavedPlaceChanges;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.util.RawValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceListCacheTest {
    @Test
    void 직접_SQL_변경도_최초_조회에서_3초_뒤에는_다시_읽는다() {
        AtomicLong now = new AtomicLong();
        PlaceListCache cache = new PlaceListCache(now::get);
        RawValue old = cache.get(1L, () -> "[]");
        now.set(PlaceListCache.TTL_NANOS - 1);
        assertThat(cache.get(1L, () -> "[2]")).isSameAs(old);
        now.incrementAndGet();
        assertThat(cache.get(1L, () -> "[2]").rawValue().toString()).isEqualTo("[2]");
    }

    @Test
    void 크거나_오랫동안_사용하지_않은_응답은_보관하지_않는다() {
        PlaceListCache cache = new PlaceListCache(() -> 0);
        cache.get(1L, () -> " ".repeat(PlaceListCache.MAX_CHARS + 1));
        RawValue small = cache.get(1L, () -> "[]");
        assertThat(small.rawValue().toString()).isEqualTo("[]");
        for (long id = 2; id <= PlaceListCache.MAX_USERS + 1; id++) {
            cache.get(id, () -> "[]");
        }
        assertThat(cache.get(1L, () -> "[1]")).isNotSameAs(small);
    }

    @Test
    void 같은_사용자의_동시_요청은_한_번만_조회한다() throws Exception {
        PlaceListCache cache = new PlaceListCache(() -> 0);
        AtomicInteger queries = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RawValue>> responses = new ArrayList<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                responses.add(workers.submit(() -> {
                    start.await();
                    return cache.get(7L, () -> "[" + queries.incrementAndGet() + "]");
                }));
            }
            start.countDown();
            for (Future<RawValue> response : responses) {
                assertThat(response.get(5, TimeUnit.SECONDS).rawValue().toString()).isEqualTo("[1]");
            }
        }
        assertThat(queries.get()).isOne();
    }

    @Test
    void 커밋_전에_시작된_조회가_늦게_끝나도_이전_값을_되살리지_않는다() throws Exception {
        PlaceListCache cache = new PlaceListCache(() -> 0);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<RawValue> old = worker.submit(() -> cache.get(7L, () -> {
                loading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("load timed out");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return "[1]";
            }));
            try {
                assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
                cache.invalidate(new SavedPlaceChanges.Changed(7L));
                RawValue fresh = cache.get(7L, () -> "[2]");
                release.countDown();
                assertThat(old.get(5, TimeUnit.SECONDS)).isNotSameAs(fresh);
                assertThat(cache.get(7L, () -> "[]")).isSameAs(fresh);
            } finally {
                release.countDown();
            }
        }
    }
}
