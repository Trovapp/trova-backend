package com.trova.backend.congestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulCongestionResponse(@JsonProperty("CITYDATA") CityData cityData) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CityData(@JsonProperty("LIVE_PPLTN_STTS") List<LivePopulation> livePopulation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LivePopulation(
            @JsonProperty("AREA_CONGEST_LVL") String areaCongestLevel,
            @JsonProperty("AREA_CONGEST_MSG") String areaCongestMessage
    ) {
    }
}
