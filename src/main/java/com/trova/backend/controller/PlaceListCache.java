package com.trova.backend.controller;

import com.trova.backend.entity.SavedPlaceChanges;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.io.SerializedString;
import tools.jackson.databind.util.RawValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
class PlaceListCache {
    static final int MAX_USERS = 64;
    // At most 2 bytes per UTF-16 code unit plus 3 for its cached UTF-8 representation.
    static final int MAX_CHARS = 32 * 1024;
    static final long TTL_NANOS = Duration.ofSeconds(3).toNanos();

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final LongSupplier clock;

    PlaceListCache() {
        this(System::nanoTime);
    }

    PlaceListCache(LongSupplier clock) {
        this.clock = clock;
    }

    RawValue get(Long userId, Supplier<String> loadJson) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A caller inside a transaction must see its own snapshot and uncommitted edits.
            return new RawValue(loadJson.get());
        }
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(userId, ignored -> new Entry());
            if (entries.size() > MAX_USERS) {
                entries.pollFirstEntry();
            }
        }
        synchronized (entry) {
            long started = clock.getAsLong();
            if (entry.body != null && started - entry.loadedAt < TTL_NANOS) {
                return entry.body;
            }
            entry.body = null;
            String json = loadJson.get();
            entry.loadedAt = started;
            if (json.length() > MAX_CHARS) {
                // Only requests already waiting on this load may share the oversized result.
                synchronized (entries) {
                    entries.remove(userId, entry);
                }
                entry.body = new RawValue(json);
            } else {
                entry.body = new RawValue(new SerializedString(json));
            }
            return entry.body;
        }
    }

    @TransactionalEventListener
    public void invalidate(SavedPlaceChanges.Changed change) {
        // Detach an in-flight reader as well. It can finish but cannot refill the new entry.
        synchronized (entries) {
            entries.remove(change.userId());
        }
    }

    private static class Entry {
        private RawValue body;
        private long loadedAt;
    }
}
