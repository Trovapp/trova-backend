package com.trova.backend.recommendation;

import java.util.Map;
import java.util.Optional;

/**
 * 사용자가 자유 입력한 카테고리 텍스트를 구글 Places API v1의 includedType
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

    private GoogleTypeMapper() {
    }

    public static Optional<String> toGoogleType(String freeTextCategory) {
        if (freeTextCategory == null || freeTextCategory.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CATEGORY_TO_GOOGLE_TYPE.get(freeTextCategory.trim()));
    }
}
