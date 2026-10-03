package com.trova.backend.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 영업시간을 받는 Text Search 응답(#106). regularOpeningHours는 Enterprise 티어(월 1,000건 무료)라
 * 일정 에이전트가 장소당 한 번만 부른다. day는 Google 기준 0=일요일. close가 없으면 24시간 영업.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePlacesHoursResponse(List<Place> places) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(String id, Location location, OpeningHours regularOpeningHours) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(Double latitude, Double longitude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpeningHours(List<Period> periods) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(Point open, Point close) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(Integer day, Integer hour, Integer minute) {
    }
}
