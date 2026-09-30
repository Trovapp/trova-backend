package com.trova.backend.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 카카오 로컬 주소 검색(/v2/local/search/address.json) 응답 중 쓰는 부분만(#61). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoAddressSearchResponse(List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            @JsonProperty("address_name") String addressName,
            String x,
            String y,
            @JsonProperty("road_address") RoadAddress roadAddress
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoadAddress(@JsonProperty("address_name") String addressName) {
    }
}
