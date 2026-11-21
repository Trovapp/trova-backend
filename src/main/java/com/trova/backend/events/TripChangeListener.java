package com.trova.backend.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TripChangeListener {
    private static final int LIMIT = 1000;
    private static final Duration TTL = Duration.ofMinutes(1);
    private record Pending(TripChangedEvent event, Instant expiresAt) {}

    private final TripEventBus bus;
    private final Executor executor;
    private final Clock clock;
    private final Counter expired;
    private final Counter evicted;
    // Insertion order is the order of the last strictly newer version for each trip.
    private final LinkedHashMap<Long, Pending> deferred = new LinkedHashMap<>();

    TripChangeListener(TripEventBus bus, @Qualifier("tripEventExecutor") Executor executor,
                       Clock clock, MeterRegistry metrics) {
        this.bus = bus;
        this.executor = executor;
        this.clock = clock;
        expired = metrics.counter("trova.trip-events.deferred.discarded", "reason", "expired");
        evicted = metrics.counter("trova.trip-events.deferred.discarded", "reason", "capacity");
        metrics.gauge("trova.trip-events.deferred.size", this, TripChangeListener::size);
    }

    private synchronized int size() { return deferred.size(); }

    @EventListener
    public synchronized void changed(TripChangedEvent event) {
        Instant now = clock.instant();
        expire(now);
        Pending existing = deferred.get(event.tripId());
        if (existing != null) {
            if (event.version() <= existing.event.version()) return;
            deferred.remove(event.tripId());
        } else if (submit(event)) {
            return;
        }

        if (deferred.size() == LIMIT) {
            deferred.pollFirstEntry();
            evicted.increment();
        }
        deferred.put(event.tripId(), new Pending(event, now.plus(TTL)));
    }

    @Scheduled(fixedDelay = 1000)
    synchronized void retry() {
        expire(clock.instant());
        while (!deferred.isEmpty() && submit(deferred.firstEntry().getValue().event)) {
            deferred.pollFirstEntry();
        }
    }

    private boolean submit(TripChangedEvent event) {
        try {
            executor.execute(() -> bus.publish(event));
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    private void expire(Instant now) {
        while (!deferred.isEmpty() && !now.isBefore(deferred.firstEntry().getValue().expiresAt)) {
            deferred.pollFirstEntry();
            expired.increment();
        }
    }
}
