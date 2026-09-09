package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlaceScoringTest {

    @Test
    void 평점과_리뷰수로_점수를_계산한다() {
        Place place = new Place("gp1", "카페", "cafe", 4.5, 10, null, 37.5, 127.0, "서울");

        double score = PlaceScoring.baseScore(place);

        // rating(4.5) * log(reviewCount(10) + 1) = 4.5 * ln(11) ≈ 10.79
        assertThat(score).isCloseTo(4.5 * Math.log(11), within(0.001));
    }

    @Test
    void 평점_리뷰수_없으면_0점이다() {
        Place place = new Place("gp2", "장소", "cafe", null, null, null, 37.5, 127.0, "서울");

        assertThat(PlaceScoring.baseScore(place)).isEqualTo(0.0);
    }
}
