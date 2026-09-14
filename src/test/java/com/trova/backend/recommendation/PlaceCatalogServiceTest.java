package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceCatalogServiceTest {

    @Mock
    private PlaceRepository placeRepository;

    @InjectMocks
    private PlaceCatalogService placeCatalogService;

    private GooglePlacesNearbySearchResponse.Place rawPlace(String id, String name) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(name), List.of("cafe"),
                4.5, 100, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }

    @Test
    void 기존에_있는_장소는_그대로_반환하고_새로_저장하지_않는다() {
        var raw = rawPlace("g1", "카페A");
        Place existing = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "서울 어딘가");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of(existing));

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).containsExactly(existing);
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 새_후보는_저장해서_반환한다() {
        var raw = rawPlace("g1", "카페A");
        when(placeRepository.findByGooglePlaceIdIn(List.of("g1"))).thenReturn(List.of());
        when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Place> result = placeCatalogService.upsertAll(List.of(raw));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getGooglePlaceId()).isEqualTo("g1");
        assertThat(result.get(0).getName()).isEqualTo("카페A");
    }
}
