package com.trova.backend.recommendation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AlternativeReasonTest {
    @Test
    void 거리와_종류를_짧게_표시한다() {
        assertThat(AlternativeReason.create(0.350, "음식점", "korean_restaurant", 0, 4.5, 100))
                .isEqualTo("원래 장소에서 직선 350m · 같은 종류의 음식점이에요");
        assertThat(AlternativeReason.create(1.25, "cafe", "cafe", 0, null, null))
                .isEqualTo("원래 장소에서 직선 1.3km · 같은 종류의 카페예요");
        assertThat(AlternativeReason.create(0.9996, null, null, 0, null, null))
                .isEqualTo("원래 장소에서 직선 1km");
    }
    @Test
    void 양수인_유한_점수에만_취향을_언급한다() {
        assertThat(AlternativeReason.create(null, null, null, 0.3, 4.5, 100))
                .isEqualTo("취향과 비슷해요 · 평점 4.5");
        for (double score : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -0.5}) {
            assertThat(AlternativeReason.create(null, "cafe", "park", score, null, null)).isNull();
        }
    }
    @Test
    void 같은_좌표는_거리_근거를_생략한다() {
        assertThat(AlternativeReason.create(0.0, "cafe", "cafe", 0, 4.5, 10))
                .isEqualTo("같은 종류의 카페예요 · 평점 4.5");
        assertThat(AlternativeReason.create(0.0001, null, null, 0, null, null)).isNull();
    }

    @Test
    void 평점은_5까지_표시한다() {
        assertThat(AlternativeReason.create(null, null, null, 0, 5.0, null)).isEqualTo("평점 5");
        assertThat(AlternativeReason.create(null, null, null, 0, 5.01, null)).isNull();
    }

    @Test
    void 종류_일치를_취향보다_먼저_표시한다() {
        assertThat(AlternativeReason.create(0.350, "cafe", "cafe", 0.8, 4.5, 100))
                .isEqualTo("원래 장소에서 직선 350m · 같은 종류의 카페예요");
        assertThat(AlternativeReason.create(0.350, "cafe", "park", 0.8, 4.5, 100))
                .isEqualTo("원래 장소에서 직선 350m · 취향과 비슷해요");
    }

    @Test
    void 없는_거리_평점_종류를_만들지_않는다() {
        assertThat(AlternativeReason.create(null, "museum", "park", 0, 4.5, 1200))
                .isEqualTo("평점 4.5 · 리뷰 1,200개");
        assertThat(AlternativeReason.create(Double.NaN, null, null, 0, null, 12)).isEqualTo("리뷰 12개");
        assertThat(AlternativeReason.create(-1.0, null, null, 0, Double.NaN, -1)).isNull();
        assertThat(AlternativeReason.create(null, null, null, 0, 0.0, 0)).isNull();
    }
}
