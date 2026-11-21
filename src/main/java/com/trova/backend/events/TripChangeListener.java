package com.trova.backend.events;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TripChangeListener {
    private final TripEventBus bus;
    private final Executor executor;
    private final ConcurrentHashMap<Long, TripChangedEvent> deferred = new ConcurrentHashMap<>();

    TripChangeListener(TripEventBus bus, @Qualifier("tripEventExecutor") Executor executor) {
        this.bus = bus;
        this.executor = executor;
    }

    @EventListener
    public void changed(TripChangedEvent event) {
        try {
            executor.execute(() -> bus.publish(event));
        } catch (RejectedExecutionException busy) {
            // Clients refetch the trip on change. Under overload retain the newest invalidation per trip.
            deferred.merge(event.tripId(), event, (a, b) -> a.version() > b.version() ? a : b);
        }
    }

    @Scheduled(fixedDelay = 1000)
    void retry() {
        for (var event : deferred.values()) {
            try {
                executor.execute(() -> bus.publish(event));
                deferred.remove(event.tripId(), event);
            } catch (RejectedExecutionException busy) {
                break;
            }
        }
    }
}
