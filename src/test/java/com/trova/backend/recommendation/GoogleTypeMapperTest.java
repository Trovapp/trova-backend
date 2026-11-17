package com.trova.backend.recommendation;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleTypeMapperTest {

    @Test
    void 매핑된_카테고리는_구글_타입을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("카페")).isEqualTo(Optional.of("cafe"));
        assertThat(GoogleTypeMapper.toGoogleType("맛집")).isEqualTo(Optional.of("restaurant"));
        assertThat(GoogleTypeMapper.toGoogleType("박물관")).isEqualTo(Optional.of("museum"));
    }

    @Test
    void 매핑에_없는_카테고리는_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType("아무말대잔치")).isEmpty();
    }

    @Test
    void null이나_공백은_빈값을_반환한다() {
        assertThat(GoogleTypeMapper.toGoogleType(null)).isEmpty();
        assertThat(GoogleTypeMapper.toGoogleType("  ")).isEmpty();
    }

    @Test
    void 카페류는_카페를_기본_검색_종류로_반환한다() {
        for (String category : new String[]{"cafe", "coffee_shop", "bakery", "dessert_shop", "tea_house",
                "breakfast", "brunch", "sandwich_shop", "카페"}) {
            assertThat(GoogleTypeMapper.defaultTypeFor(category)).as(category).contains("cafe");
        }
    }

    @Test
    void 관광류는_관광지를_기본_검색_종류로_반환한다() {
        for (String category : new String[]{"tourist_attraction", "park", "museum", "buddhist_temple",
                "hindu_temple", "amusement_park", "zoo", "aquarium", "art_gallery", "박물관", "공원"}) {
            assertThat(GoogleTypeMapper.defaultTypeFor(category)).as(category).contains("tourist_attraction");
        }
    }

    @Test
    void 숙소류는_숙소를_기본_검색_종류로_반환한다() {
        for (String category : new String[]{"lodging", "hotel", "resort_hotel", "extended_stay_hotel",
                "guest_house", "motel", "hostel", "호텔"}) {
            assertThat(GoogleTypeMapper.defaultTypeFor(category)).as(category).contains("lodging");
        }
    }

    @Test
    void 쇼핑류는_쇼핑몰을_기본_검색_종류로_반환한다() {
        for (String category : new String[]{"shopping_mall", "department_store", "clothing_store",
                "grocery_store", "market", "쇼핑"}) {
            assertThat(GoogleTypeMapper.defaultTypeFor(category)).as(category).contains("shopping_mall");
        }
    }

    @Test
    void 식당류는_식당을_기본_검색_종류로_반환한다() {
        for (String category : new String[]{"restaurant", "korean_restaurant", "breakfast_restaurant", "식당"}) {
            assertThat(GoogleTypeMapper.defaultTypeFor(category)).as(category).contains("restaurant");
        }
    }

    @Test
    void 알수없는_분류와_빈_분류는_기본_검색_종류가_없다() {
        assertThat(GoogleTypeMapper.defaultTypeFor("unknown")).isEmpty();
        assertThat(GoogleTypeMapper.defaultTypeFor("bridge")).isEmpty();
        assertThat(GoogleTypeMapper.defaultTypeFor(null)).isEmpty();
        assertThat(GoogleTypeMapper.defaultTypeFor("  ")).isEmpty();
    }

}
