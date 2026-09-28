package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Places 원시 후보를 Place 카탈로그에 upsert한다(배치 조회로 N+1 방지).
 * RecommendationService(근처 검색)와 PlaceSearchService(텍스트 검색) 양쪽에서 공유한다.
 */
@Service
public class PlaceCatalogService {

    private final PlaceRepository placeRepository;

    public PlaceCatalogService(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    /**
     * 후보를 카탈로그에 저장하고 입력 순서대로 돌려준다. 이미 있는 장소는 그대로 쓴다.
     * 없는 장소는 INSERT ... ON CONFLICT DO NOTHING으로 넣고 다시 조회한다 — 같은 검색 결과를
     * 여러 요청이 동시에 저장해도(여러 사용자의 같은 동네 검색 등) 유니크 제약 에러가 나지 않는다(#37).
     */
    public List<Place> upsertAll(List<GooglePlacesNearbySearchResponse.Place> rawCandidates) {
        List<String> googleIds = rawCandidates.stream()
                .map(GooglePlacesNearbySearchResponse.Place::id)
                .distinct()
                .toList();
        Map<String, Place> byGoogleId = new HashMap<>();
        placeRepository.findByGooglePlaceIdIn(googleIds).forEach(p -> byGoogleId.put(p.getGooglePlaceId(), p));

        Map<String, GooglePlacesNearbySearchResponse.Place> missing = new LinkedHashMap<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            if (!byGoogleId.containsKey(raw.id())) {
                missing.putIfAbsent(raw.id(), raw);
            }
        }
        if (!missing.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (GooglePlacesNearbySearchResponse.Place raw : missing.values()) {
                String category = raw.types() != null && !raw.types().isEmpty() ? raw.types().get(0) : null;
                String name = raw.displayName() != null ? raw.displayName().text() : null;
                Double lat = raw.location() != null ? raw.location().latitude() : null;
                Double lng = raw.location() != null ? raw.location().longitude() : null;
                placeRepository.insertIfAbsent(
                        raw.id(), name, category, raw.rating(), raw.userRatingCount(),
                        raw.priceLevel(), lat, lng, raw.formattedAddress(), now);
            }
            placeRepository.findByGooglePlaceIdIn(List.copyOf(missing.keySet()))
                    .forEach(p -> byGoogleId.put(p.getGooglePlaceId(), p));
        }

        List<Place> result = new ArrayList<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            Place place = byGoogleId.get(raw.id());
            if (place != null) {
                result.add(place);
            }
        }
        return result;
    }
}
