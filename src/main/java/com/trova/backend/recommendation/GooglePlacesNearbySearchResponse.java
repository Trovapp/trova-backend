package com.trova.backend.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Places API (New)의 searchNearby 응답. 비용을 낮은 티어(Basic/Pro)로만 유지하려고
 * 필드마스크를 최소로 요청한다 — Enterprise 티어(리뷰 텍스트, editorialSummary 등)는
 * 요청하지 않는다(GooglePlacesApiClientImpl의 FIELD_MASK 참고).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlacesNearbySearchResponse(List<Place> places) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            String id,
            DisplayName displayName,
            List<String> types,
            Double rating,
            Integer userRatingCount,
            String priceLevel,
            Location location,
            String formattedAddress
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record DisplayName(String text) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Location(double latitude, double longitude) {
        }
    }
}
