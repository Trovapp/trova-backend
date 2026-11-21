package com.trova.backend.events;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class TripStreams {
    private final Set<TripStream> streams = ConcurrentHashMap.newKeySet();
    private final ExecutorService senders = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("trip-sse-", 0).factory());
    private final TripAudience audience;
    private final Clock clock;
    private final int perUser;
    private final int total;
    TripStreams(TripAudience audience, Clock clock, @Value("${app.trip-events.per-user:3}") int perUser,
                @Value("${app.trip-events.total:256}") int total) {
        this.audience = audience;
        this.clock = clock;
        this.perUser = perUser;
        this.total = total;
    }

    synchronized TripStream open(long trip, long user, StreamAuth auth) {
        long version = audience.version(trip, user);
        long timeout = Duration.between(clock.instant(), auth.end()).toMillis();
        if (timeout <= 0 || !auth.validAt(clock.instant())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (streams.size() >= total || streams.stream().filter(s -> s.user == user).count() >= perUser)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        var stream = new TripStream(trip, user, auth, clock, timeout, senders, streams::remove);
        streams.add(stream);
        stream.offer(SseEmitter.event().name("ready").data(Map.of("version", version)));
        senders.execute(stream);
        return stream;
    }

    @PreDestroy
    void shutdown() {
        streams.forEach(TripStream::close);
        senders.shutdownNow();
    }

    boolean viewing(long trip, long user) {
        return streams.stream().anyMatch(s -> s.trip == trip && s.user == user && s.active());
    }

    private synchronized List<TripStream> snapshot() { return List.copyOf(streams); }

    @org.springframework.context.event.EventListener(condition = "#event.type() == 'MEMBER_REMOVED'")
    public void membershipChanged(TripChangedEvent event) {
        var targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        var members = audience.members(event.tripId());
        targets.stream().filter(s -> !members.contains(s.user)).forEach(TripStream::close);
    }

    void deliver(TripChangedEvent event) {
        var targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        var members = audience.members(event.tripId());
        for (var stream : targets) {
            if (members.contains(stream.user)) stream.offer(SseEmitter.event().name("changed").data(event));
            else stream.close();
        }
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        var members = new java.util.HashMap<Long, List<Long>>();
        for (var stream : snapshot()) {
            if (members.computeIfAbsent(stream.trip, audience::members).contains(stream.user))
                stream.offer(SseEmitter.event().comment("heartbeat"));
            else stream.close();
        }
    }
}
