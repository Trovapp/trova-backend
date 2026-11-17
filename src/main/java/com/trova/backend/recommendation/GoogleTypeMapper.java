package com.trova.backend.recommendation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사용자가 자유 입력한 카테고리와 원본 장소의 분류를 구글 Places API v1의 includedType
 * 열거값으로 매핑한다. Google이 받는 값은 고정된 타입 문자열뿐이라 자유 텍스트를
 * 그대로 넘길 수 없다 — 자주 쓰는 것만 우선 매핑하고, 매핑에 없으면 빈 값을
 * 반환해서 호출부가 반경 검색만 하고 이름/카테고리 텍스트로 후처리 필터링하게
 * 한다(검색 자체가 실패하지 않도록).
 */
public final class GoogleTypeMapper {

    private static final Map<String, String> CATEGORY_TO_GOOGLE_TYPE = Map.ofEntries(
            Map.entry("카페", "cafe"),
            Map.entry("커피", "cafe"),
            Map.entry("맛집", "restaurant"),
            Map.entry("음식점", "restaurant"),
            Map.entry("식당", "restaurant"),
            Map.entry("박물관", "museum"),
            Map.entry("미술관", "art_gallery"),
            Map.entry("쇼핑", "shopping_mall"),
            Map.entry("공원", "park"),
            Map.entry("술집", "bar"),
            Map.entry("바", "bar"),
            Map.entry("숙소", "lodging"),
            Map.entry("호텔", "lodging"),
            Map.entry("관광", "tourist_attraction"),
            Map.entry("영화관", "movie_theater")
    );

    private static final Set<String> ATTRACTION_TYPES = Set.of(
            "tourist_attraction", "attraction", "park", "national_park", "state_park", "museum",
            "temple", "amusement_park", "zoo", "aquarium", "art_gallery", "historical_landmark",
            "historical_place", "cultural_landmark", "monument", "beach", "botanical_garden");

    private GoogleTypeMapper() {
    }

    public static Optional<String> defaultTypeFor(String category) {
        if (category == null || category.isBlank()) {
            return Optional.empty();
        }
        String type = toGoogleType(category).orElse(category.trim());
        if (type.equals("restaurant") || type.endsWith("_restaurant")) {
            return Optional.of("restaurant");
        }
        if (isMorningFriendly(type)) {
            return Optional.of("cafe");
        }
        if (type.equals("lodging") || type.equals("hotel") || type.endsWith("_hotel")
                || type.equals("guest_house") || type.equals("motel") || type.equals("hostel")) {
            return Optional.of("lodging");
        }
        if (type.equals("shopping") || type.equals("shopping_mall") || type.equals("department_store")
                || type.endsWith("_store") || type.equals("market")) {
            return Optional.of("shopping_mall");
        }
        if (ATTRACTION_TYPES.contains(type) || type.endsWith("_temple")) {
            return Optional.of("tourist_attraction");
        }
        return Optional.empty();
    }

    static boolean isMorningFriendly(String category) {
        if (category == null) {
            return false;
        }
        return category.contains("cafe") || category.contains("coffee") || category.contains("bakery")
                || category.contains("breakfast") || category.contains("brunch") || category.contains("sandwich")
                || category.contains("dessert") || category.contains("tea_house");
    }

    public static Optional<String> toGoogleType(String freeTextCategory) {
        if (freeTextCategory == null || freeTextCategory.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CATEGORY_TO_GOOGLE_TYPE.get(freeTextCategory.trim()));
    }
}
