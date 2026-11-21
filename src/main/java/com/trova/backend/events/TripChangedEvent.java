package com.trova.backend.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record TripChangedEvent(long tripId, long version, long actorId, String actorName, String type, String summary,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Long> membersAtDeletion) {
    public static final String TRIP_DELETED = "TRIP_DELETED";

    public TripChangedEvent {
        membersAtDeletion = membersAtDeletion == null ? List.of() : List.copyOf(membersAtDeletion);
    }

    public TripChangedEvent(long tripId, long version, long actorId, String actorName, String type, String summary) {
        this(tripId, version, actorId, actorName, type, summary, List.of());
    }

    boolean deleted() { return TRIP_DELETED.equals(type); }

    TripChangedEvent forClient() {
        return membersAtDeletion.isEmpty() ? this : new TripChangedEvent(tripId, version, actorId, actorName, type, summary);
    }
}
