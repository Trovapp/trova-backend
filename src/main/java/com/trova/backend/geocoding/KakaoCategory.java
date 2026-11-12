package com.trova.backend.geocoding;

/**
 * 카카오 장소의 category_name("음식점 > 카페 > 디저트카페")을 앱이 쓰는 분류로 바꾼다.
 * 영상 속 장소의 분류는 Gemini가 영상 맥락만 보고 골라 빙수집이 "관광명소"가 되기도 했다 —
 * 같은 가게가 찜·리뷰에서는 "디저트"로 보였다(안드로이드 사용성 테스트 2026-11-12, #414).
 * 카카오에서 실제 가게를 찾았으면 그 분류를 먼저 쓰고, 알 수 없는 분류(역·병원 등)는 null로 돌려 Gemini 값을 쓴다.
 */
public final class KakaoCategory {

    private KakaoCategory() {
    }

    public static String toCategory(String kakaoCategoryName) {
        if (kakaoCategoryName == null || kakaoCategoryName.isBlank()) {
            return null;
        }
        String name = kakaoCategoryName;
        // "음식점 > 카페 > …"라서 카페·디저트를 음식점보다 먼저 본다.
        if (name.contains("카페") || name.contains("디저트")) {
            return "cafe";
        }
        if (name.startsWith("음식점")) {
            return "restaurant";
        }
        if (name.startsWith("숙박")) {
            return "lodging";
        }
        if (name.startsWith("여행") || name.contains("관광")) {
            return "attraction";
        }
        if (name.contains("쇼핑")) {
            return "shopping";
        }
        return null;
    }

    /** 카카오 분류가 있으면 그것을, 없거나 모르는 분류면 추출 때 고른 분류를 쓴다. */
    public static String resolve(String kakaoCategoryName, String extractedCategory) {
        String fromKakao = toCategory(kakaoCategoryName);
        return fromKakao != null ? fromKakao : extractedCategory;
    }
}
