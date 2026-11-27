package com.trova.backend.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;

public class SavedPlaceChanges {
    public record Changed(Long userId) {}

    private final ApplicationEventPublisher events;

    public SavedPlaceChanges(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(SavedPlace place) {
        events.publishEvent(new Changed(place.getUser().getId()));
    }
}
