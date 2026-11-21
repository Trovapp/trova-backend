package com.trova.backend.events;

import com.trova.backend.push.ExpoPushService;
import com.trova.backend.push.PushMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TripChangePush {
    record Window(long trip, long actor, long recipient) {}
    private final ConcurrentHashMap<Window, Instant> windows = new ConcurrentHashMap<>();
    private final TripAudience audience;
    private final TripStreams streams;
    private final ExpoPushService expo;
    private final Clock clock;
    TripChangePush(TripAudience audience, TripStreams streams, ExpoPushService expo, Clock clock) {
        this.audience = audience;
        this.streams = streams;
        this.expo = expo;
        this.clock = clock;
    }
    @Async("pushTaskExecutor")
    @EventListener
    public void changed(TripChangedEvent event) {
        for (long user : audience.members(event.tripId())) {
            if (user == event.actorId() || streams.viewing(event.tripId(), user)) continue;
            Window key = new Window(event.tripId(), event.actorId(), user);
            Instant now = clock.instant();
            Instant end = now.plusSeconds(300);
            Instant previous = windows.putIfAbsent(key, end);
            if (previous != null && (now.isBefore(previous) || !windows.replace(key, previous, end))) continue;
            expo.sendToUser(user, "trip-change", new PushMessage(event.actorName() + "님이 여행을 고쳤어요",
                    event.summary(), "trip:" + event.tripId(), "trip-" + event.tripId()));
        }
    }
    @Scheduled(fixedDelay = 300000)
    void prune() { windows.entrySet().removeIf(w -> !clock.instant().isBefore(w.getValue())); }
}
