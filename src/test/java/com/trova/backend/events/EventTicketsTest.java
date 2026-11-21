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
        var ticket = tickets.issue(1L, 2L, now.plusSeconds(300));
        assertThat(tickets.consume(1L, ticket).user()).isEqualTo(2L);
        assertThatThrownBy(() -> tickets.consume(1L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void expiresAtSixtySeconds() {
        when(clock.instant()).thenReturn(now);
        var ticket = tickets.issue(1L, 2L, now.plusSeconds(300));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(() -> tickets.consume(1L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void cannotOpenAnotherTrip() {
        when(clock.instant()).thenReturn(now);
        var ticket = tickets.issue(1L, 2L, now.plusSeconds(300));
        assertThatThrownBy(() -> tickets.consume(3L, ticket)).isInstanceOf(ResponseStatusException.class);
    }
}
