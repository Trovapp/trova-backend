package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceTest {

    @Test
    void applyReviewSummary는_요약과_생성시각을_함께_저장한다() {
        Place place = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");

        place.applyReviewSummary("전반적으로 만족도가 높은 곳이에요.");

        assertThat(place.getReviewSummary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
        assertThat(place.getReviewSummaryGeneratedAt()).isNotNull();
        assertThat(place.getReviewSummaryGeneratedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }
}
