package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private GooglePlacesApiClient googlePlacesApiClient;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private PlaceTaggingRunner placeTaggingRunner;

    @InjectMocks
    private RecommendationService recommendationService;

    private GooglePlacesNearbySearchResponse.Place rawPlace(
            String id, String name, String category, Double rating, Integer reviewCount
    ) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(name), List.of(category),
                rating, reviewCount, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "서울 어딘가");
    }

    @Test
    void 새_후보는_저장하고_리뷰없는_후보는_필터링하고_태깅해서_반환한다() {
        var withReviews = rawPlace("g1", "카페A", "cafe", 4.5, 100);
        var noReviews = rawPlace("g2", "무명카페", "cafe", null, 0);
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 1000))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(withReviews, noReviews)));
        when(placeRepository.findByGooglePlaceIdIn(any())).thenReturn(List.of());
        when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));
        when(placeTaggingRunner.run(any(), anyLong()))
                .thenReturn(List.of(new PlaceTag(0, "TRENDY", "INDOOR")));

        List<Place> result = recommendationService.recommend(37.5, 127.0, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("카페A");
        assertThat(result.get(0).getMood()).isEqualTo("TRENDY");
        assertThat(result.get(0).getSpace()).isEqualTo("INDOOR");

        ArgumentCaptor<List<PlaceTaggingRunner.TagCandidate>> captor = ArgumentCaptor.forClass(List.class);
        verify(placeTaggingRunner).run(captor.capture(), anyLong());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void 이미_태깅된_장소는_다시_태깅하지_않는다() {
        var raw = rawPlace("g1", "카페A", "cafe", 4.5, 100);
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place alreadyTagged = new Place("g1", "카페A", "cafe", 4.5, 100, "PRICE_LEVEL_MODERATE", 37.5, 127.0, "주소");
        alreadyTagged.applyTags("CALM", "INDOOR");
        when(placeRepository.findByGooglePlaceIdIn(any())).thenReturn(List.of(alreadyTagged));

        List<Place> result = recommendationService.recommend(37.5, 127.0, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMood()).isEqualTo("CALM");
        verify(placeTaggingRunner, never()).run(any(), anyLong());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 검색_결과가_없으면_빈_리스트를_반환하고_아무것도_호출하지_않는다() {
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        List<Place> result = recommendationService.recommend(37.5, 127.0, 1000);

        assertThat(result).isEmpty();
        verify(placeRepository, never()).findByGooglePlaceIdIn(any());
        verify(placeTaggingRunner, never()).run(any(), anyLong());
    }
}
