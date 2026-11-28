package com.trova.backend.repository;

import java.time.LocalDateTime;

public interface PlaceCatalogUpdates {
    record BookmarkFieldsChanged(Long placeId) {}

    int updateSpaceTag(Long id, String space, LocalDateTime taggedAt);
    int repairMissingLocation(Long id, double latitude, double longitude, String address);
}
