package com.trova.backend.recommendation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceNameMatchTest {

    @Test
    void 지점명이_달라도_같은_상호면_같은_가게로_본다() {
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트 서울명동성당점", "이삭토스트 홍대점")).isTrue();
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트", "이삭토스트 서울명동성당점")).isTrue();
    }

    @Test
    void 같은_건물의_다른_가게는_다른_가게로_본다() {
        // 이삭토스트 자리에서 설빙 리뷰가 요약됐다(안드로이드 사용성 테스트 2026-11-12, #414).
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트 서울명동성당점", "설빙 명동2호점")).isFalse();
    }

    @Test
    void 이름이_없으면_막지_않는다() {
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트", null)).isTrue();
    }
}
