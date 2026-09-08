package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlaceTest {

    private TripPlace newTripPlace() {
        return new TripPlace(
                null, "돈사돈", "제주", "음식점", 33.4, 126.5, null, "제주 노형동",
                1, PlaceSource.NORMAL, null);
    }

    @Test
    void applyGooglePlaceId는_값을_저장한다() {
        TripPlace place = newTripPlace();

        place.applyGooglePlaceId("g-donsadon");

        assertThat(place.getGooglePlaceId()).isEqualTo("g-donsadon");
    }

    @Test
    void applyDetails는_null이_아닌_필드만_갱신한다() {
        TripPlace place = newTripPlace();
        place.applyDetails(LocalTime.of(11, 0), LocalTime.of(12, 30), TransportMode.WALK, "고기 맛집");

        place.applyDetails(null, null, TransportMode.CAR, null);

        assertThat(place.getVisitStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(place.getVisitEndTime()).isEqualTo(LocalTime.of(12, 30));
        assertThat(place.getArrivalTransportMode()).isEqualTo(TransportMode.CAR);
        assertThat(place.getMemo()).isEqualTo("고기 맛집");
    }
}
