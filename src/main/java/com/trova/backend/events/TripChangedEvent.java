package com.trova.backend.events;

public record TripChangedEvent(long tripId, long version, long actorId, String actorName, String type, String summary) {}
