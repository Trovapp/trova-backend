package com.trova.backend.geocoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoCategoryTest {

    @Test
    void 디저트카페는_음식점보다_먼저_카페로_본다() {
        assertThat(KakaoCategory.toCategory("음식점 > 카페 > 디저트카페")).isEqualTo("cafe");
        assertThat(KakaoCategory.toCategory("음식점 > 한식 > 해물,생선 > 아구")).isEqualTo("restaurant");
    }

    @Test
    void 숙박_여행_쇼핑을_나누고_모르는_분류는_null() {
        assertThat(KakaoCategory.toCategory("숙박 > 호텔")).isEqualTo("lodging");
        assertThat(KakaoCategory.toCategory("여행 > 공원 > 도시근린공원")).isEqualTo("attraction");
        assertThat(KakaoCategory.toCategory("가정,생활 > 쇼핑,유통 > 백화점")).isEqualTo("shopping");
        assertThat(KakaoCategory.toCategory("교통,수송 > 기차,철도 > 기차역")).isNull();
        assertThat(KakaoCategory.toCategory(null)).isNull();
    }

    @Test
    void 카카오_분류가_있으면_추출_분류보다_먼저_쓴다() {
        assertThat(KakaoCategory.resolve("음식점 > 카페 > 디저트카페", "attraction")).isEqualTo("cafe");
        assertThat(KakaoCategory.resolve("교통,수송 > 기차,철도 > 기차역", "attraction")).isEqualTo("attraction");
        assertThat(KakaoCategory.resolve(null, "restaurant")).isEqualTo("restaurant");
    }
}
