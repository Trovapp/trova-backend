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
    void 시설과_입점한_매장은_같은_장소가_아니다() {
        for (String facility : java.util.List.of("롯데면세점", "현대백화점중동점", "롯데백화점", "알수없는시설중동점", "롯데몰", "서울역", "김포공항", "롯데몰점", "서울역점", "김포공항점")) {
            assertThat(PlaceNameMatch.sameCatalogName(facility, "나이키 " + facility)).isFalse();
            assertThat(PlaceNameMatch.sameCatalogName("나이키 " + facility, facility)).isFalse();
        }
        assertThat(PlaceNameMatch.sameCatalogName("나이키 서울롯데몰점", "나이키 롯데몰점")).isTrue();
    }

    @Test
    void 브랜드와_지점이_같을_때만_지역명과_R_표기를_허용한다() {
        assertThat(PlaceNameMatch.sameCatalogName("스타벅스 강남점", "스타벅스 강남R점")).isTrue();
        assertThat(PlaceNameMatch.sameCatalogName("스타벅스 강남역삼점", "스타벅스 역삼점")).isTrue();
        assertThat(PlaceNameMatch.sameCatalogName("스타벅스 강남점", "스타벅스 홍대R점")).isFalse();
        assertThat(PlaceNameMatch.sameCatalogName("나이키 명동성당점", "명동성당점")).isFalse();
        assertThat(PlaceNameMatch.sameCatalogName("이삭토스트 부산명동성당점", "명동성당점")).isFalse();
        assertThat(PlaceNameMatch.sameCatalogName("경포호", "경포호(鏡浦湖)")).isTrue();
    }

    @Test
    void 이름이_없으면_막지_않는다() {
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트", null)).isTrue();
    }
}
