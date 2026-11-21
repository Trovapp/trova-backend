package com.trova.backend.events;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.web.server.ResponseStatusException;

class EventTicketsTest {
    Clock clock = mock(Clock.class);
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    EventTickets tickets = new EventTickets(clock);
    @Test void ticketCanOnlyBeUsedOnce() {
        when(clock.instant()).thenReturn(now);
        var ticket = tickets.issue(1L, 2L, new StreamAuth(now.plusSeconds(300), null));
        assertThat(tickets.consume(1L, ticket).user()).isEqualTo(2L);
        assertThatThrownBy(() -> tickets.consume(1L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void expiresAtSixtySeconds() {
        when(clock.instant()).thenReturn(now);
        var ticket = tickets.issue(1L, 2L, new StreamAuth(now.plusSeconds(300), null));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(() -> tickets.consume(1L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void outstandingTicketsAreLimitedPerUserAcrossTrips() {
        when(clock.instant()).thenReturn(now);
        String first = tickets.issue(1, 2, new StreamAuth(now.plusSeconds(300), null));
        for (int i = 1; i < 8; i++) tickets.issue(i, 2, new StreamAuth(now.plusSeconds(300), null));
        assertThatThrownBy(() -> tickets.issue(9, 2, new StreamAuth(now.plusSeconds(300), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
        tickets.issue(9, 3, new StreamAuth(now.plusSeconds(300), null));
        tickets.consume(1, first);
        tickets.issue(9, 2, new StreamAuth(now.plusSeconds(300), null));
    }

    @Test void cannotOpenAnotherTrip() {
        when(clock.instant()).thenReturn(now);
        var ticket = tickets.issue(1L, 2L, new StreamAuth(now.plusSeconds(300), null));
        assertThatThrownBy(() -> tickets.consume(3L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
}
