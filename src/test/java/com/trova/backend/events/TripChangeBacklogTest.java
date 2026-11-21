package com.trova.backend.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripChangeBacklogTest {
    final Clock clock = mock(Clock.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ArrayList<TripChangedEvent> sent = new ArrayList<>();
    boolean busy = true;
    final TripChangeListener listener = new TripChangeListener(sent::add, task -> {
        if (busy) throw new RejectedExecutionException();
        task.run();
    }, clock, metrics);

    private TripChangedEvent change(long trip, long version) {
        return new TripChangedEvent(trip, version, 1, "친구", "TRIP_TITLE", "이름 변경");
    }

    @Test void overflowKeepsMostRecentlyUpdatedThousandTrips() {
        when(clock.instant()).thenReturn(Instant.EPOCH);
        for (int id = 1; id <= 1000; id++) listener.changed(change(id, 1));
        listener.changed(change(1, 2));
        listener.changed(change(1001, 1));
        assertThat(metrics.get("trova.trip-events.deferred.size").gauge().value()).isEqualTo(1000);
        busy = false;
        listener.retry();
        assertThat(sent).hasSize(1000).contains(change(1, 2), change(1001, 1)).doesNotContain(change(2, 1));
        assertThat(metrics.get("trova.trip-events.deferred.discarded").tag("reason", "capacity").counter().count()).isOne();
    }

    @Test void olderVersionCannotExtendLifetimeAndExpiryRunsWhileExecutorIsBusy() {
        when(clock.instant()).thenReturn(Instant.EPOCH);
        listener.changed(change(1, 4));
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(59));
        listener.changed(change(1, 3));
        listener.changed(change(2, 1));
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(60));
        listener.retry();
        assertThat(metrics.get("trova.trip-events.deferred.discarded").tag("reason", "expired").counter().count()).isOne();
        assertThat(metrics.get("trova.trip-events.deferred.size").gauge().value()).isOne();
        busy = false;
        listener.retry();
        assertThat(sent).containsExactly(change(2, 1));
    }
}
