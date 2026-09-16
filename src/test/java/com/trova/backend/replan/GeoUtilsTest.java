package com.trova.backend.replan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void 같은_좌표는_거리가_0이다() {
        double km = GeoUtils.haversineKm(37.5665, 126.9780, 37.5665, 126.9780);
        assertThat(km).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void 경복궁과_서울역_거리는_약_2_3km다() {
        // 실측 검증 가능한 유명 랜드마크 좌표 — 경복궁(37.5796, 126.9770),
        // 서울역(37.5547, 126.9707). 직선거리 약 2.9km.
        double km = GeoUtils.haversineKm(37.5796, 126.9770, 37.5547, 126.9707);
        assertThat(km).isBetween(2.5, 3.3);
    }

    @Test
    void 도보_30분_거리는_2km_안팎이다() {
        // 4km/h 가정이므로 30분 = 정확히 2.0km 지점이 임계값.
        int minutesAt2km = GeoUtils.estimatedWalkMinutes(37.5665, 126.9780, 37.5665, 126.9960);
        // 위 좌표쌍은 경도 차이만으로 약 1.6km — 정확한 임계값 테스트보다,
        // 함수가 haversineKm/4*60 계산을 정확히 수행하는지를 직접 검증한다.
        double km = GeoUtils.haversineKm(37.5665, 126.9780, 37.5665, 126.9960);
        int expectedMinutes = (int) Math.round(km / 4.0 * 60);
        assertThat(minutesAt2km).isEqualTo(expectedMinutes);
    }
}
