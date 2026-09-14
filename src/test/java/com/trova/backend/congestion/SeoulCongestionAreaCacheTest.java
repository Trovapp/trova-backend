package com.trova.backend.congestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulCongestionAreaCacheTest {

    @Test
    void 등록된_장소명은_true를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea("경복궁")).isTrue();
    }

    @Test
    void 등록_안된_장소명은_false를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea("아무개카페")).isFalse();
    }

    @Test
    void null은_false를_반환한다() {
        assertThat(SeoulCongestionAreaCache.isKnownArea(null)).isFalse();
    }
}
