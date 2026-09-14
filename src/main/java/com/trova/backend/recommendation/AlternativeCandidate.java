package com.trova.backend.recommendation;

public record AlternativeCandidate(
        Long placeId,
        String googlePlaceId,
        String name,
        String category,
        Double rating,
        Integer userRatingCount,
        Double latitude,
        Double longitude,
        String address,
        Double distanceToNextKm,
        Integer estimatedTravelMinutes,
        Boolean isCongestionAvailable,
        String congestionLevel,
        String recommendationReason
) {
}
