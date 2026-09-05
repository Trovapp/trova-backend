package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public List<Place> upsertAll(List<GooglePlacesNearbySearchResponse.Place> rawCandidates) {
        List<String> googleIds = rawCandidates.stream()
                .map(GooglePlacesNearbySearchResponse.Place::id)
                .toList();
        Map<String, Place> existingByGoogleId = placeRepository.findByGooglePlaceIdIn(googleIds).stream()
                .collect(Collectors.toMap(Place::getGooglePlaceId, p -> p));

        List<Place> result = new ArrayList<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            Place existing = existingByGoogleId.get(raw.id());
            if (existing != null) {
                result.add(existing);
                continue;
            }
            String category = raw.types() != null && !raw.types().isEmpty() ? raw.types().get(0) : null;
            String name = raw.displayName() != null ? raw.displayName().text() : null;
            Double lat = raw.location() != null ? raw.location().latitude() : null;
            Double lng = raw.location() != null ? raw.location().longitude() : null;
            Place created = placeRepository.save(new Place(
                    raw.id(), name, category, raw.rating(), raw.userRatingCount(),
                    raw.priceLevel(), lat, lng, raw.formattedAddress()));
            result.add(created);
        }
        return result;
    }
}
