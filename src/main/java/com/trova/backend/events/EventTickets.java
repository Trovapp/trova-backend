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
    record Grant(long trip, long user, Instant authEnd, Instant redeemBy) {}
    private final Map<String, Grant> pending = new HashMap<>();
    private final Clock clock;
    EventTickets(Clock clock) { this.clock = clock; }

    synchronized String issue(long trip, long user, Instant authEnd) {
        if (pending.size() >= 4096) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        String ticket = UUID.randomUUID().toString();
        pending.put(ticket, new Grant(trip, user, authEnd, clock.instant().plusSeconds(60)));
        return ticket;
    }

    synchronized Grant consume(long trip, String ticket) {
        Grant grant = pending.remove(ticket);
        Instant now = clock.instant();
        if (grant == null || grant.trip != trip || !now.isBefore(grant.redeemBy) || !now.isBefore(grant.authEnd))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return grant;
    }

    @Scheduled(fixedDelay = 60000)
    synchronized void prune() {
        pending.values().removeIf(g -> !clock.instant().isBefore(g.redeemBy));
    }
}
