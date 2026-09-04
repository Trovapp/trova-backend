package com.trova.backend.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** OpenWeatherMap "5 day / 3 hour forecast" 응답(무료 티어). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenWeatherForecastResponse(List<Entry> list) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            long dt,
            @com.fasterxml.jackson.annotation.JsonProperty("dt_txt") String dtText,
            // pop: 강수확률(0.0~1.0)
            Double pop
    ) {
    }
}
