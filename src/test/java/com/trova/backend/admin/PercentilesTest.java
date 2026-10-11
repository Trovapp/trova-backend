package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void 가장_가까운_순위_방식으로_백분위를_구한다() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertThat(Percentiles.of(values, 0.5)).isEqualTo(50L);
        assertThat(Percentiles.of(values, 0.9)).isEqualTo(90L);
        assertThat(Percentiles.of(List.of(7L), 0.9)).isEqualTo(7L);
    }

    @Test
    void 값이_없으면_null이다() {
        assertThat(Percentiles.of(List.of(), 0.5)).isNull();
    }
}
