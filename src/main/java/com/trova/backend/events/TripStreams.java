package com.trova.backend.events;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class TripStreams {
    record Stream(long trip, long user, Instant end, SseEmitter emitter) {}
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
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

    synchronized SseEmitter open(long trip, long user, Instant end) {
        long version = audience.version(trip, user);
        long timeout = Duration.between(clock.instant(), end).toMillis();
        if (timeout <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (streams.size() >= total || streams.stream().filter(s -> s.user == user).count() >= perUser)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        var emitter = new SseEmitter(timeout);
        var stream = new Stream(trip, user, end, emitter);
        emitter.onCompletion(() -> streams.remove(stream));
        emitter.onError(e -> streams.remove(stream));
        emitter.onTimeout(() -> close(stream));
        streams.add(stream);
        send(stream, SseEmitter.event().name("ready").data(Map.of("version", version)));
        return emitter;
    }

    boolean viewing(long trip, long user) {
        return streams.stream().anyMatch(s -> s.trip == trip && s.user == user && clock.instant().isBefore(s.end));
    }

    private synchronized List<Stream> snapshot() { return List.copyOf(streams); }

    @org.springframework.context.event.EventListener(condition = "#event.type() == 'MEMBER_REMOVED'")
    public void membershipChanged(TripChangedEvent event) {
        var targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        var members = audience.members(event.tripId());
        targets.stream().filter(s -> !members.contains(s.user)).forEach(this::close);
    }

    void deliver(TripChangedEvent event) {
        var targets = snapshot().stream().filter(s -> s.trip == event.tripId()).toList();
        if (targets.isEmpty()) return;
        var members = audience.members(event.tripId());
        for (var stream : targets) {
            if (members.contains(stream.user)) send(stream, SseEmitter.event().name("changed").data(event));
            else close(stream);
        }
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        var members = new java.util.HashMap<Long, List<Long>>();
        for (var stream : snapshot()) {
            if (members.computeIfAbsent(stream.trip, audience::members).contains(stream.user))
                send(stream, SseEmitter.event().comment("heartbeat"));
            else close(stream);
        }
    }

    private void send(Stream stream, SseEmitter.SseEventBuilder message) {
        if (!clock.instant().isBefore(stream.end)) { close(stream); return; }
        try { stream.emitter.send(message); }
        catch (IOException | IllegalStateException e) { close(stream); }
    }

    private void close(Stream stream) {
        streams.remove(stream);
        stream.emitter.complete();
    }
}
