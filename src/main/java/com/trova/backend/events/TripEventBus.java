package com.trova.backend.events;

public interface TripEventBus {
    void publish(TripChangedEvent event);
}
