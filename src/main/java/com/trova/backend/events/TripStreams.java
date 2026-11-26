package com.trova.backend.events;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class TripStreams {
    private final Set<TripStream> streams = new LinkedHashSet<>();
    private final Set<TripStream> writers = new LinkedHashSet<>();
    private final HashMap<Long, Instant> replacementAllowedAt = new HashMap<>();
    private final ExecutorService senders = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("trip-sse-", 0).factory());
    private final TripAudience audience;
    private final Clock clock;
    private final int perUser;
    private final int total;
    private final Duration replacementCooldown;
    private final Counter replaced;
    private record Registration(TripStream stream, TripStream evicted) {}
    TripStreams(TripAudience audience, Clock clock, @Value("${app.trip-events.per-user:3}") int perUser,
                @Value("${app.trip-events.total:256}") int total,
                @Value("${app.trip-events.replacement-cooldown:30s}") Duration replacementCooldown,
                MeterRegistry metrics) {
        this.audience = audience;
        this.clock = clock;
        this.perUser = perUser;
        this.total = total;
        this.replacementCooldown = replacementCooldown;
        metrics.gauge("trova.trip-events.active", this, TripStreams::connectionCount);
        metrics.gauge("trova.trip-events.writers", this, TripStreams::writerCount);
        this.replaced = metrics.counter("trova.trip-events.replaced");
    }

    TripStream open(long trip, long user, StreamAuth auth) {
        long version = audience.version(trip, user);
        Registration registration = register(trip, user, auth, version);
        // Termination can touch the servlet transport; keep it outside the admission monitor.
        if (registration.evicted() != null) registration.evicted().replaced();
        TripStream stream = registration.stream();
        try {
            senders.execute(stream);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            stream.close();
            writerFinished(stream);
            throw stopped;
        }
        return stream;
    }

    private synchronized Registration register(long trip, long user, StreamAuth auth, long version) {
        Instant now = clock.instant();
        long timeout = Duration.between(now, auth.end()).toMillis();
        if (timeout <= 0 || !auth.validAt(now)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        replacementAllowedAt.values().removeIf(deadline -> !now.isBefore(deadline));
        List<TripStream> owned = streams.stream().filter(s -> s.user == user).toList();
        // Bound all admitted writer tasks, including closed transports still unwinding.
        if (writers.size() >= 2L * total || perUser <= 0)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        TripStream evicted = null;
        long openOwned = owned.stream().filter(TripStream::replaceable).count();
        if (openOwned >= perUser) {
            // Older clients reconnect after `replaced`; let their existing 429 backoff limit churn.
            if (replacementAllowedAt.containsKey(user))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            evicted = owned.stream().filter(TripStream::reserveReplacement).findFirst().orElse(null);
        }
        // A replacement does not add a slot, even when the global pool is full.
        if (evicted == null && streams.size() >= total)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        TripStream stream = new TripStream(trip, user, version, auth, clock, timeout, senders, this::release, this::writerFinished);
        if (evicted != null) {
            streams.remove(evicted);
            replacementAllowedAt.put(user, now.plus(replacementCooldown));
            replaced.increment();
        }
        streams.add(stream);
        writers.add(stream);
        return new Registration(stream, evicted);
    }

    private synchronized void release(TripStream stream) { streams.remove(stream); }

    private synchronized void writerFinished(TripStream stream) { writers.remove(stream); }

    private synchronized int writerCount() { return writers.size(); }

    private synchronized int connectionCount() { return streams.size(); }

    @PreDestroy
    void shutdown() {
        writerSnapshot().forEach(TripStream::close);
        senders.shutdownNow();
    }

    boolean viewing(long trip, long user) {
        return snapshot().stream().anyMatch(s -> s.trip == trip && s.user == user && s.active());
    }

    private synchronized List<TripStream> snapshot() { return List.copyOf(streams); }

    private synchronized List<TripStream> writerSnapshot() { return List.copyOf(writers); }

    @org.springframework.context.event.EventListener(condition = "#event.type() == 'MEMBER_REMOVED'")
    public void membershipChanged(TripChangedEvent event) {
        List<TripStream> targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        TripAudience.Snapshot state = audience.snapshot(event.tripId());
        if (!state.tripGone()) targets.stream().filter(s -> !state.members().contains(s.user)).forEach(s -> s.membershipLost(false));
    }

    @org.springframework.context.event.EventListener(condition = "#event.type() == 'TRIP_DELETED'")
    public void deleted(TripChangedEvent event) {
        for (TripStream stream : snapshot()) {
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
        List<TripStream> targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        TripAudience.Snapshot state = audience.snapshot(event.tripId());
        if (state.tripGone()) return;
        for (TripStream stream : targets) {
            if (state.members().contains(stream.user)) stream.changed(event);
            else stream.membershipLost(false);
        }
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        // Eviction releases the slot before its writer ends; keep watching stalled writes.
        writerSnapshot().forEach(TripStream::checkWriteTimeout);
        HashMap<Long, TripAudience.Snapshot> trips = new HashMap<>();
        for (TripStream stream : snapshot()) {
            TripAudience.Snapshot state = trips.computeIfAbsent(stream.trip, audience::snapshot);
            if (state.members().contains(stream.user)) stream.heartbeat(state.version());
            else stream.membershipLost(state.tripGone());
        }
    }
}
