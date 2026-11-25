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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "한빛 카페,한빛 식당,false",
            "한빛 카페,한빛 베이커리,false",
            "서울 호텔 명동점,호텔 서울 명동점,false",
            "달빛,달빛(分店),false",
            "달빛,달빛(支店),false",
            "달빛,달빛(별관),false",
            "달빛,달빛(未知),false",
            "교보문고광화문점,교보문고 광화문,true",
            "카페 서울숲 성수점,카페 숲 성수점,false",
            "투썸 플레이스 서울명동점,투썸 플레이스 명동점,true",
            "교보문고 광화문점,교보문고 광화문,true",
            "한빛시장(東館),한빛시장(西館),false",
            "한빛시장(1관),한빛시장(2관),false",
            "한빛시장(1관),한빛시장,false",
            "롯데면세점,나이키 롯데면세점,false",
            "현대백화점중동점,나이키 현대백화점중동점,false",
            "롯데백화점,나이키 롯데백화점,false",
            "서울역,나이키 서울역,false",
            "이삭토스트 서울명동성당점,명동성당점,true",
            "이삭토스트 서울명동성당점,이삭토스트 명동성당점,true",
            "이삭토스트 서울명동성당점,이삭토스트 부산명동성당점,false",
            "이삭토스트 서울명동성당점,이삭토스트 홍대점,false",
            "이삭토스트 부산명동성당점,명동성당점,false",
            "설빙 명동성당점,명동성당점,false",
            "스타벅스 강남점,스타벅스 강남R점,true",
            "스타벅스 강남역삼점,스타벅스 역삼점,true",
            "스타벅스 강남점,스타벅스 홍대R점,false",
            "CU 강남점,GS25 강남점,false",
            "CU 강남점,CU 홍대점,false",
            "경포호,경포호(鏡浦湖),true",
            "경포호,경포호호텔,false",
            "카페,카페,false"
    })
    void 카탈로그_이름은_양방향으로_비교한다(String first, String second, boolean match) {
        assertThat(PlaceNameMatch.sameCatalogName(first, second)).isEqualTo(match);
        assertThat(PlaceNameMatch.sameCatalogName(second, first)).isEqualTo(match);
    }

    @Test
    void 이름이_없으면_막지_않는다() {
        assertThat(PlaceNameMatch.sameBusiness("이삭토스트", null)).isTrue();
    }
}
