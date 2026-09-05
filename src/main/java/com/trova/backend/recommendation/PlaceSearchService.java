package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import org.springframework.stereotype.Service;

import java.util.List;

/** 사용자가 이름으로 검색한 장소 후보를 반환한다(여행에 수동으로 장소를 추가할 때 사용). */
@Service
public class PlaceSearchService {

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;

    public PlaceSearchService(GooglePlacesApiClient googlePlacesApiClient, PlaceCatalogService placeCatalogService) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
    }

    public List<Place> search(String query) {
        List<GooglePlacesNearbySearchResponse.Place> raw = googlePlacesApiClient.searchText(query).places();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return placeCatalogService.upsertAll(raw);
    }
}
