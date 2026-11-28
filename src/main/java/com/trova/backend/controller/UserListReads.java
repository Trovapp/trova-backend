package com.trova.backend.controller;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** 단일 JVM에서 인증과 존재 확인을 마친 사용자 ID로 목록을 구분한다. */
@Component
final class UserListReads {
    private final AsyncCache<Long, List<PlacesController.PlaceResponse>> places = cache();
    private final AsyncCache<Long, List<BookmarkController.BookmarkResponse>> bookmarks = cache();
    private final AsyncCache<Long, List<TripController.TripResponse>> trips = cache();

    List<PlacesController.PlaceResponse> places(Long userId, Supplier<List<PlacesController.PlaceResponse>> load) {
        return read(places, userId, load);
    }

    List<BookmarkController.BookmarkResponse> bookmarks(Long userId, Supplier<List<BookmarkController.BookmarkResponse>> load) {
        return read(bookmarks, userId, load);
    }

    List<TripController.TripResponse> trips(Long userId, Supplier<List<TripController.TripResponse>> load) {
        return read(trips, userId, load);
    }

    private static <T> List<T> read(AsyncCache<Long, List<T>> cache, Long userId, Supplier<List<T>> load) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return load.get();
        CompletableFuture<List<T>> future = cache.getIfPresent(userId);
        if (future == null) {
            CompletableFuture<List<T>> pending = new CompletableFuture<>();
            future = cache.asMap().putIfAbsent(userId, pending);
            if (future == null) {
                // 맵에는 future만 등록한다. JDBC는 요청 스레드에서 맵 잠금을 놓은 뒤 실행한다.
                try {
                    List<T> rows = load.get();
                    pending.complete(rows);
                    return rows;
                } catch (RuntimeException | Error failure) {
                    cache.asMap().remove(userId, pending);
                    pending.completeExceptionally(failure);
                    throw failure;
                }
            }
        }
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }

    void invalidatePlaces(Set<Long> users) {
        users.forEach(places.asMap()::remove);
    }

    void invalidateBookmarks(Set<Long> users, Set<Long> changedPlaces) {
        users.forEach(bookmarks.asMap()::remove);
        if (changedPlaces.isEmpty()) return;
        // 조회 중인 결과는 아직 어느 장소를 포함하는지 알 수 없다. 완료된 목록은 해당 장소만 찾는다.
        bookmarks.asMap().entrySet().removeIf(entry -> {
            CompletableFuture<List<BookmarkController.BookmarkResponse>> rows = entry.getValue();
            return rows.state() != Future.State.SUCCESS
                    || rows.resultNow().stream().anyMatch(row -> changedPlaces.contains(row.placeId()));
        });
    }

    void invalidateTrips(Set<Long> viewers, boolean sharedAudienceUnknown) {
        if (sharedAudienceUnknown) trips.asMap().clear();
        else viewers.forEach(trips.asMap()::remove);
    }

    private static <T> AsyncCache<Long, List<T>> cache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(20)).maximumWeight(512)
                .weigher((Long userId, List<T> rows) -> Math.max(1, rows.size())).buildAsync();
    }
}
