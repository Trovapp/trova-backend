package com.trova.backend.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 대안 상위 두 곳에만 표시하는 근거 문구. 외부 조회 없이 확인된 값 두 개까지 쓴다. */
final class AlternativeReason {
    private static final Map<String, String> SAME_CATEGORY = Map.of(
            "restaurant", "같은 종류의 음식점이에요", "cafe", "같은 종류의 카페예요",
            "museum", "같은 종류의 박물관이에요", "park", "같은 종류의 공원이에요",
            "art_gallery", "같은 종류의 미술관이에요", "lodging", "같은 종류의 숙소예요",
            "shopping_mall", "같은 종류의 쇼핑몰이에요", "bar", "같은 종류의 술집이에요");

    private AlternativeReason() {
    }

    static String create(Double distanceKm, String targetCategory, String category,
                         double score, Double rating, Integer reviews) {
        List<String> facts = new ArrayList<>();
        if (distanceKm != null && Double.isFinite(distanceKm) && distanceKm >= 0) {
            long meters = Math.round(distanceKm * 1000);
            // 같은 좌표와 반올림하면 0m인 거리는 유용한 거리 근거가 아니다.
            if (meters > 0) {
                String distance = meters < 1000 ? meters + "m" : decimal(distanceKm) + "km";
                facts.add("원래 장소에서 직선 " + distance);
            }
        }
        String type = normalizedCategory(category);
        if (type != null && type.equals(normalizedCategory(targetCategory)) && SAME_CATEGORY.containsKey(type)) {
            facts.add(SAME_CATEGORY.get(type));
        }
        // 0점은 신호 없음·조회 실패도 뜻한다. 음수/NaN/무한대도 취향 근거로 쓰지 않는다(ADR 0051).
        if (Double.isFinite(score) && score > 0) {
            facts.add("취향과 비슷해요");
        }
        if (rating != null && Double.isFinite(rating) && rating > 0 && rating <= 5) {
            facts.add("평점 " + decimal(rating));
        }
        if (reviews != null && reviews > 0) {
            facts.add("리뷰 " + String.format(Locale.KOREA, "%,d", reviews) + "개");
        }
        return facts.isEmpty() ? null : String.join(" · ", facts.subList(0, Math.min(2, facts.size())));
    }

    private static String normalizedCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String type = GoogleTypeMapper.toGoogleType(category).orElse(category.trim());
        // 검색용 defaultTypeFor는 박물관과 공원도 합치므로 이유의 종류 일치에는 쓰지 않는다.
        return type.endsWith("_restaurant") ? "restaurant" : type;
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
