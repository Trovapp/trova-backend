package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GapStopPolicy {
    private GapStopPolicy() {}

    private static final Set<String> EXCLUDED = Set.of(
            "apartment_building", "apartment_complex", "condominium_complex", "housing_complex",
            "hospital", "general_hospital", "doctor", "medical_center", "public_health_department", "medical_clinic",
            "medical_lab", "skin_care_clinic", "chiropractor", "physiotherapist", "dental_clinic", "dentist",
            "pharmacy", "drugstore",
            "school", "primary_school", "secondary_school", "middle_school", "high_school", "preschool", "private_tutor",
            "government_office", "local_government_office", "city_hall", "post_office", "courthouse", "embassy",
            "fire_station", "police", "neighborhood_police_station",
            "factory", "manufacturer", "warehouse", "storage", "logistics_service", "shipping_service",
            "courier_service", "moving_company", "supplier", "corporate_office", "business_center", "coworking_space",
            "car_repair", "bank", "atm", "gas_station",
            "parking", "parking_garage", "parking_lot", "real_estate_agency", "insurance_agency", "place_of_worship", "shinto_shrine", "buddhist_temple",
            "hindu_temple", "church", "mosque", "synagogue", "funeral_home", "cemetery",
            "bus_stop", "bus_station", "train_station", "subway_station", "transit_station",
            "transit_stop", "light_rail_station", "airport",
            "아파트", "빌라", "오피스텔", "병원", "의원", "치과", "약국", "보건소", "초등학교", "중학교",
            "고등학교", "학원", "공공기관", "행정기관", "시청", "구청", "주민센터", "행정복지센터", "우체국", "경찰서", "법원", "기업", "공장",
            "물류센터", "물류창고", "자동차정비", "자동차정비,수리", "자동차수리", "은행", "현금인출기", "주유소", "주차장", "부동산",
            "보험", "종교", "장례식장", "묘지", "버스정류장", "지하철역", "기차역", "철도역", "지하철,전철", "보험회사", "부동산중개");

    private static final Set<String> TOURISM = Set.of(
            "tourist_attraction", "historical", "historical_landmark", "historical_place", "cultural_landmark",
            "monument", "museum", "park", "city_park", "historical_site", "national_park", "state_park", "memorial_park",
            "관광명소", "관광,명소", "문화유적", "문화유적지", "문화재", "박물관", "공원");
    private static final Set<String> FOOD = Set.of("food", "restaurant", "cafe", "bakery", "coffee_shop",
            "bar", "dessert_shop", "tea_house", "음식점", "카페");
    private static final Set<String> RETAINED = Set.of("university", "대학교", "대학", "convenience_store",
            "편의점", "lodging", "hotel", "motel", "hostel", "guest_house", "resort_hotel", "extended_stay_hotel", "숙박");
    private static final List<String> EXCLUDED_ENDINGS = List.of(
            "아파트", "빌라", "오피스텔", "관리사무소", "의원", "병원", "치과", "약국", "보건소",
            "초등학교", "중학교", "고등학교", "학원", "시청", "군청", "구청", "주민센터", "행정복지센터",
            "우체국", "경찰서", "지구대", "법원", "공장", "물류센터", "물류창고", "본사", "사무실",
            "카센터", "카센타", "자동차정비소", "자동차서비스프라자", "은행", "ATM", "주유소", "주차장",
            "부동산", "공인중개사", "공인중개사사무소", "보험대리점", "교회", "성당", "사찰", "장례식장",
            "묘지", "정류장", "정거장");

    private static final Set<String> GENERIC_TYPES = Set.of("point_of_interest", "establishment",
            "premise", "subpremise", "street_address", "geocode", "political");
    private static final List<String> AREA_ENDINGS = List.of("구역", "지역", "해역", "권역");
    // 일반 태그만 있는 역 중 지명과 '역'을 붙이면 '구역'으로 끝나는 확인된 사례.
    private static final Set<String> AMBIGUOUS_STATION_NAMES = Set.of("대구역", "동대구역");

    static boolean allows(GooglePlacesNearbySearchResponse.Place source, Place stored, List<String> kakaoPaths) {
        Set<String> labels = new HashSet<>();
        if (source.types() != null) labels.addAll(source.types());
        labels.remove(null);
        labels.removeAll(GENERIC_TYPES);
        String cached = stored.getCategory();
        if (labels.isEmpty() && cached != null && !GENERIC_TYPES.contains(cached)) {
            labels.add(cached);
        }
        for (String path : kakaoPaths) {
            Arrays.stream(path.split(">")).map(String::strip).forEach(labels::add);
        }
        if (labels.stream().anyMatch(TOURISM::contains)) return true;
        // Google이 대학교에 school도 붙인 경우 대학교 정책을 우선한다.
        if (labels.contains("university") || labels.contains("대학교") || labels.contains("대학")) {
            labels.remove("school");
        }
        if (labels.stream().anyMatch(EXCLUDED::contains)) return false;
        if (labels.stream().anyMatch(label -> RETAINED.contains(label) || FOOD.contains(label)
                || label.endsWith("_restaurant") || label.endsWith("_cafe"))) return true;

        String name = source.displayName() == null ? null : source.displayName().text();
        if (name == null || name.isBlank()) name = stored.getName();
        if (name == null) return true;
        String compact = name.replaceAll("\\s+", "");
        if (labels.isEmpty() && compact.length() > 1 && compact.endsWith("역")) {
            return !AMBIGUOUS_STATION_NAMES.contains(compact) && AREA_ENDINGS.stream().anyMatch(compact::endsWith);
        }
        return EXCLUDED_ENDINGS.stream().noneMatch(compact::endsWith);
    }
}
