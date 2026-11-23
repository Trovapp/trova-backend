package com.trova.backend.events;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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

    TripStream open(long trip, long user, StreamAuth auth) {
        long version = audience.version(trip, user);
        TripStream stream = register(trip, user, auth, version);
        senders.execute(stream);
        return stream;
    }

    private synchronized TripStream register(long trip, long user, StreamAuth auth, long version) {
        long timeout = Duration.between(clock.instant(), auth.end()).toMillis();
        if (timeout <= 0 || !auth.validAt(clock.instant())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (streams.size() >= total || streams.stream().filter(s -> s.user == user).count() >= perUser)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        var stream = new TripStream(trip, user, version, auth, clock, timeout, senders, streams::remove);
        streams.add(stream);
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
        var state = audience.snapshot(event.tripId());
        if (!state.tripGone()) targets.stream().filter(s -> !state.members().contains(s.user)).forEach(s -> s.membershipLost(false));
    }

    @org.springframework.context.event.EventListener(condition = "#event.type() == 'TRIP_DELETED'")
    public void deleted(TripChangedEvent event) {
        for (var stream : snapshot()) {
            if (stream.trip != event.tripId()) continue;
            if (event.membersAtDeletion().contains(stream.user)) stream.finish(event);
            else stream.close();
        }
    }

    void deliver(TripChangedEvent event) {
        if (event.deleted()) {
            deleted(event);
            return;
        }
        var targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        var state = audience.snapshot(event.tripId());
        if (state.tripGone()) return;
        for (var stream : targets) {
            if (state.members().contains(stream.user)) stream.changed(event);
            else stream.membershipLost(false);
        }
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        var trips = new java.util.HashMap<Long, TripAudience.Snapshot>();
        for (var stream : snapshot()) {
            var state = trips.computeIfAbsent(stream.trip, audience::snapshot);
            if (state.members().contains(stream.user)) stream.heartbeat(state.version());
            else stream.membershipLost(state.tripGone());
        }
    }
}
