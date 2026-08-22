package com.trova.backend.geocoding;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record KakaoKeywordSearchResponse(List<Document> documents) {
    public record Document(
            @JsonProperty("place_name") String placeName,
            String x,
            String y,
            String phone,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("place_url") String placeUrl
    ) {
    }
}
