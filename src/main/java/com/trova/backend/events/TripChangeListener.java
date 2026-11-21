package com.trova.backend.events;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class TripChangeListener {
    private final TripEventBus bus;
    TripChangeListener(TripEventBus bus) { this.bus = bus; }

    @Async("tripEventExecutor")
    @EventListener
    public void changed(TripChangedEvent event) { bus.publish(event); }
}
