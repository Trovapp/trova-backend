package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceSearchServiceTest {

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private PlaceCatalogService placeCatalogService;

    @InjectMocks
    private PlaceSearchService placeSearchService;

    @Test
    void 검색결과를_카탈로그에_upsert해서_반환한다() {
        var raw = new GooglePlacesNearbySearchResponse.Place(
                "g1", new GooglePlacesNearbySearchResponse.Place.DisplayName("경복궁"), List.of("tourist_attraction"),
                4.6, 5000, null, new GooglePlacesNearbySearchResponse.Place.Location(37.58, 126.97), "서울 종로구");
        when(googlePlacesApiClient.searchText("경복궁"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        Place upserted = new Place("g1", "경복궁", "tourist_attraction", 4.6, 5000, null, 37.58, 126.97, "서울 종로구");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(upserted));

        List<Place> result = placeSearchService.search("경복궁");

        assertThat(result).containsExactly(upserted);
    }

    @Test
    void 검색결과가_없으면_빈리스트를_반환하고_upsert를_호출하지_않는다() {
        when(googlePlacesApiClient.searchText("없는장소"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        List<Place> result = placeSearchService.search("없는장소");

        assertThat(result).isEmpty();
        verify(placeCatalogService, never()).upsertAll(any());
    }
}
