package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
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

    @Mock
    private UserPreferenceRepository userPreferenceRepository;

    @InjectMocks
    private RecommendationService recommendationService;

    private final User user = new User("google", "recommendation-test", "테스트유저", null);

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
        when(userPreferenceRepository.findByUser(user)).thenReturn(List.of());

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

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
        when(userPreferenceRepository.findByUser(user)).thenReturn(List.of());

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMood()).isEqualTo("CALM");
        verify(placeTaggingRunner, never()).run(any(), anyLong());
        verify(placeRepository, never()).save(any());
    }

    @Test
    void 검색_결과가_없으면_빈_리스트를_반환하고_아무것도_호출하지_않는다() {
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result).isEmpty();
        verify(placeRepository, never()).findByGooglePlaceIdIn(any());
        verify(placeTaggingRunner, never()).run(any(), anyLong());
    }

    @Test
    void 선호_mood와_일치하면_점수가_낮아도_더_위로_올라간다() {
        // A: 평점 높음, mood 태그 없음(선호 매칭 안 됨) / B: 평점 낮음, 선호 mood와 일치
        var high = rawPlace("gA", "높은평점", "cafe", 5.0, 1000);
        var low = rawPlace("gB", "선호매칭", "cafe", 3.0, 10);
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(high, low)));
        when(placeRepository.findByGooglePlaceIdIn(any())).thenReturn(List.of());
        when(placeRepository.save(any(Place.class))).thenAnswer(inv -> inv.getArgument(0));
        when(placeTaggingRunner.run(any(), anyLong())).thenAnswer(inv -> {
            List<PlaceTaggingRunner.TagCandidate> candidates = inv.getArgument(0);
            return candidates.stream()
                    .map(c -> new PlaceTag(c.index(), c.name().equals("선호매칭") ? "CALM" : "TRENDY", "INDOOR"))
                    .toList();
        });
        when(userPreferenceRepository.findByUser(user))
                .thenReturn(List.of(new com.trova.backend.entity.UserPreference(user, "CALM", 100.0)));

        List<Place> result = recommendationService.recommend(user, 37.5, 127.0, 1000);

        assertThat(result.get(0).getName()).isEqualTo("선호매칭");
    }
}
