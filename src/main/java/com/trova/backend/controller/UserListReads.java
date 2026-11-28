package com.trova.backend.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 단일 JVM의 목록 DTO만 재사용한다. 키는 인증·존재 확인을 마친 사용자 ID다. */
@Component
final class UserListReads implements TransactionExecutionListener {
    private final Semaphore coldRequests;
    private volatile Lists current;

    UserListReads(@Value("${app.list-reads.max-concurrent-misses:32}") int maxMisses) {
        if (maxMisses < 1) throw new IllegalArgumentException("max-concurrent-misses must be positive");
        this.coldRequests = new Semaphore(maxMisses);
    }

    List<PlacesController.PlaceResponse> places(Long userId, Supplier<List<PlacesController.PlaceResponse>> load) {
        return read(lists().places, userId, load);
    }

    List<BookmarkController.BookmarkResponse> bookmarks(Long userId, Supplier<List<BookmarkController.BookmarkResponse>> load) {
        return read(lists().bookmarks, userId, load);
    }

    List<TripController.TripResponse> trips(Long userId, Supplier<List<TripController.TripResponse>> load) {
        return read(lists().trips, userId, load);
    }

    private <T> List<T> read(Cache<Long, List<T>> cache, Long userId, Supplier<List<T>> load) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return load.get();
        List<T> hit = cache.getIfPresent(userId);
        if (hit != null) return hit;
        // DB 작업과 같은 키를 기다리는 요청까지 제한한다. 히트는 슬롯을 쓰지 않는다.
        if (!coldRequests.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try {
            return cache.get(userId, ignored -> load.get());
        } finally {
            coldRequests.release();
        }
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable failure) {
        if (!transaction.isReadOnly() && failure == null) current = null;
    }

    private Lists lists() {
        Lists cached = current;
        return cached != null ? cached : newListsIfAbsent();
    }

    private synchronized Lists newListsIfAbsent() {
        Lists cached = current;
        if (cached == null) {
            cached = new Lists();
            current = cached;
        }
        return cached;
    }

    // 세대 교체 전에 시작한 조회는 옛 캐시에만 쓴다. 공유 여행·장소·회원 변경도 한꺼번에 반영한다.
    private static final class Lists {
        final Cache<Long, List<PlacesController.PlaceResponse>> places = cache();
        final Cache<Long, List<BookmarkController.BookmarkResponse>> bookmarks = cache();
        final Cache<Long, List<TripController.TripResponse>> trips = cache();

        private static <T> Cache<Long, List<T>> cache() {
            // 목록마다 총 512행. 빈 배열도 1행으로 계산해 사용자 키 수를 제한한다.
            return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(20)).maximumWeight(512)
                    .weigher((Long userId, List<T> rows) -> Math.max(1, rows.size())).build();
        }
    }
}
