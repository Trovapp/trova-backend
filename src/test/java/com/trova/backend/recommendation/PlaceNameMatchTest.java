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
    void 카탈로그_지점_별칭은_다른_브랜드나_다른_지점과_구분한다() {
        String wanted = "이삭토스트 서울명동성당점";
        assertThat(PlaceNameMatch.sameCatalogName(wanted, "이삭토스트 명동성당점")).isTrue();
        assertThat(PlaceNameMatch.sameCatalogName(wanted, "명동성당점")).isTrue();
        assertThat(PlaceNameMatch.sameCatalogName(wanted, "설빙 명동성당점")).isFalse();
        assertThat(PlaceNameMatch.sameCatalogName(wanted, "이삭토스트 명동역점")).isFalse();
        assertThat(PlaceNameMatch.sameCatalogName(wanted, "이삭토스트 부산명동성당점")).isFalse();
    }

    @Test
    void 이름이_없으면_막지_않는다() {
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트", null)).isTrue();
    }
}
