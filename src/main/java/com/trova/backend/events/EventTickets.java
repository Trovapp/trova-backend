package com.trova.backend.events;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EventTickets {
    record Grant(long trip, long user, StreamAuth auth, Instant redeemBy) {}
    private static final int GLOBAL_LIMIT = 4096;
    private static final int USER_LIMIT = 8;
    private final Map<String, Grant> pending = new HashMap<>();
    private final Clock clock;
    EventTickets(Clock clock) { this.clock = clock; }

    synchronized String issue(long trip, long user, StreamAuth auth) {
        if (atLimit(user)) {
            prune();
            if (atLimit(user)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        String ticket = UUID.randomUUID().toString();
        pending.put(ticket, new Grant(trip, user, auth, clock.instant().plusSeconds(60)));
        return ticket;
    }

    private boolean atLimit(long user) {
        return pending.size() >= GLOBAL_LIMIT
                || pending.values().stream().filter(g -> g.user == user).limit(USER_LIMIT).count() == USER_LIMIT;
    }

    synchronized Grant consume(long trip, String ticket) {
        Grant grant = pending.remove(ticket);
        Instant now = clock.instant();
        if (grant == null || grant.trip != trip || !now.isBefore(grant.redeemBy) || !grant.auth.validAt(now))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return grant;
    }

    @Scheduled(fixedDelay = 60000)
    synchronized void prune() {
        Instant now = clock.instant();
        pending.values().removeIf(g -> !now.isBefore(g.redeemBy) || !g.auth.validAt(now));
    }
}
