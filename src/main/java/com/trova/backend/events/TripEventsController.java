package com.trova.backend.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import com.trova.backend.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/trips/{id}/events")
class TripEventsController {
    private final TripStreams streams;
    private final TripAudience audience;
    private final EventTickets tickets;
    private final CurrentUserService users;
    private final Clock clock;
    TripEventsController(TripStreams streams, TripAudience audience, EventTickets tickets, CurrentUserService users, Clock clock) {
        this.streams = streams;
        this.audience = audience;
        this.tickets = tickets;
        this.users = users;
        this.clock = clock;
    }

    @PostMapping("/ticket")
    ResponseEntity<?> ticket(@PathVariable long id, Authentication auth, HttpServletRequest request) {
        long user = users.resolveUserId(auth);
        audience.version(id, user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ticket", tickets.issue(id, user, expiry(request))));
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> open(@PathVariable long id, @RequestParam(required = false) String ticket,
                                     Authentication auth, HttpServletRequest request) {
        TripStream stream;
        if (ticket == null) stream = streams.open(id, users.resolveUserId(auth), expiry(request));
        else {
            var grant = tickets.consume(id, ticket);
            stream = streams.open(id, grant.user(), grant.auth());
        }
        request.setAttribute(TripSseMvc.STREAM, stream);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stream.emitter);
    }

    private StreamAuth expiry(HttpServletRequest request) {
        if (request.getAttribute("trova.jwt.expiresAt") instanceof Instant end) return new StreamAuth(end, null);
        var session = request.getSession(false);
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        int seconds = session.getMaxInactiveInterval();
        Instant end = seconds > 0 ? clock.instant().plusSeconds(seconds) : Instant.ofEpochMilli(Long.MAX_VALUE);
        return new StreamAuth(end, session);
    }
}
