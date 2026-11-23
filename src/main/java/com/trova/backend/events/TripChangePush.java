package com.trova.backend.events;

import com.trova.backend.planner.Josa;
import com.trova.backend.push.ExpoPushService;
import com.trova.backend.push.PushMessage;
import com.trova.backend.push.PushNotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TripChangePush {
    private static final Logger log = LoggerFactory.getLogger(TripChangePush.class);
    private final Executor executor;
    private final Counter discarded;
    record Window(long trip, long actor, long recipient) {}
    private final ConcurrentHashMap<Window, Instant> windows = new ConcurrentHashMap<>();
    private final TripAudience audience;
    private final TripStreams streams;
    private final ExpoPushService expo;
    private final Clock clock;
    TripChangePush(TripAudience audience, TripStreams streams, ExpoPushService expo, Clock clock,
                   @Qualifier("tripPushExecutor") Executor executor, MeterRegistry metrics) {
        this.executor = executor;
        this.discarded = metrics.counter("trova.trip-push.discarded");
        this.audience = audience;
        this.streams = streams;
        this.expo = expo;
        this.clock = clock;
    }
    @EventListener
    public void changed(TripChangedEvent event) {
        try {
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException full) {
            if (event.deleted()) {
                send(event);
            } else {
                discarded.increment();
                log.warn("여행 변경 푸시 대기열 초과: trip={}", event.tripId());
            }
        }
    }

    private void send(TripChangedEvent event) {
        if (event.deleted()) {
            String name = event.actorName() == null || event.actorName().isBlank() ? "만든 사람" : event.actorName();
            var message = new PushMessage(Josa.iGa(name) + " 여행을 삭제했어요", event.summary(), "",
                    "trip-" + event.tripId());
            for (long user : event.membersAtDeletion()) {
                if (user == event.actorId()) continue;
                try {
                    expo.sendToUser(user, "trip-deleted", message);
                } catch (RuntimeException failure) {
                    log.warn("여행 삭제 푸시 발송 실패: trip={}", event.tripId(), failure);
                }
            }
            return;
        }
        for (long user : audience.members(event.tripId())) {
            if (user == event.actorId() || streams.viewing(event.tripId(), user)) continue;
            Window key = new Window(event.tripId(), event.actorId(), user);
            Instant now = clock.instant();
            Instant end = now.plusSeconds(300);
            Instant previous = windows.putIfAbsent(key, end);
            if (previous != null && (now.isBefore(previous) || !windows.replace(key, previous, end))) continue;
            try {
                PushNotificationService.Result result = expo.sendToUser(user, "trip-change", new PushMessage(
                        event.actorName() + "님이 여행을 고쳤어요", event.summary(), "trip:" + event.tripId(), "trip-" + event.tripId()));
                if (result.sent() == 0 && result.failed() > 0) windows.remove(key, end);
            } catch (RuntimeException failure) {
                windows.remove(key, end);
                log.warn("여행 변경 푸시 발송 실패: trip={}", event.tripId(), failure);
            }
        }
    }
    @Scheduled(fixedDelay = 300000)
    void prune() { windows.entrySet().removeIf(w -> !clock.instant().isBefore(w.getValue())); }
}
