package com.trova.backend.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Places API (New)의 getDetails 응답. reviews는 Enterprise + Atmosphere 티어(유료)라
 * DETAILS_FIELD_MASK를 통해 이 호출에서만 요청한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlacesDetailsResponse(String id, List<Review> reviews) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(ReviewText text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewText(String text) {
    }
}
