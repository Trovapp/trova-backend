package com.trova.backend.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.trip-events.type", havingValue = "memory", matchIfMissing = true)
class MemoryTripEventBus implements TripEventBus {
    private final TripStreams streams;
    MemoryTripEventBus(TripStreams streams) { this.streams = streams; }
    public void publish(TripChangedEvent event) { streams.deliver(event); }
}
