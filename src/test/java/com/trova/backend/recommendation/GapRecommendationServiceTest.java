package com.trova.backend.recommendation;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GapRecommendationServiceTest {

    @Mock private TripRepository tripRepository;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @Mock private ApiCallLogService apiCallLogService;
    @Mock private PlaceEmbeddingService placeEmbeddingService;
    @InjectMocks private GapRecommendationService gapRecommendationService;

    // User.id는 영속화되지 않은 순수 Mockito 단위 테스트 엔티티에서는 null이라
    // findGaps 내부의 `.getId().equals(...)` 소유자 확인이 NPE 없이 돌아가려면
    // 가짜 id를 심어줘야 한다 — AlternativeFinderServiceTest와 동일한 기법.
    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 시간이_없는_장소_쌍은_gap으로_잡지_않는다() {
        User user = new User("google", "gap1", "갭유저1", null);
        setId(user, 1L);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.6, 127.1, null, null, 2, PlaceSource.NORMAL, null);
        // 시간 미입력 — visitStartTime/visitEndTime 둘 다 null

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void _30분_넘게_비면_gap으로_잡고_중간지점을_검색한다() {
        User user = new User("google", "gap2", "갭유저2", null);
        setId(user, 1L);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        a.applyDetails(null, LocalTime.of(10, 0), null, null);
        b.applyDetails(LocalTime.of(11, 0), null, null, null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));
        when(googlePlacesApiClient.searchNearby(37.505, 127.000, 1500, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).gapMinutes()).isEqualTo(60);
    }

    @Test
    void _30분_이하로_비면_gap으로_안_잡는다() {
        User user = new User("google", "gap3", "갭유저3", null);
        setId(user, 1L);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        a.applyDetails(null, LocalTime.of(10, 0), null, null);
        b.applyDetails(LocalTime.of(10, 20), null, null, null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void 이전_이후_장소_자기_자신은_추천에서_제외된다() {
        User user = new User("google", "gap5", "갭유저5", null);
        setId(user, 1L);
        Trip trip = new Trip(user, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace a = new TripPlace(itinerary, "A", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace b = new TripPlace(itinerary, "B", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        a.applyDetails(null, LocalTime.of(10, 0), null, null);
        b.applyDetails(LocalTime.of(11, 0), null, null, null);
        a.applyGooglePlaceId("gp-a");
        b.applyGooglePlaceId("gp-b");

        var rawA = new GooglePlacesNearbySearchResponse.Place(
                "gp-a", new GooglePlacesNearbySearchResponse.Place.DisplayName("A"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.505, 127.000), null);
        var rawOther = new GooglePlacesNearbySearchResponse.Place(
                "gp-other", new GooglePlacesNearbySearchResponse.Place.DisplayName("다른곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.506, 127.000), null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));
        when(itineraryRepository.findByTripAndDay(trip, 1)).thenReturn(Optional.of(itinerary));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(a, b));
        // 중간지점(37.500/37.510 평균)은 부동소수점 연산 결과라 리터럴 37.505와 비트가
        // 정확히 일치하지 않을 수 있다(정확한 값은 37.504999999999995) — anyDouble()로
        // 매칭해서 이 테스트의 의도(자기 자신 후보 제외)와 무관한 정밀도 이슈를 피한다.
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawA, rawOther)));

        Place placeA = new Place("gp-a", "A", "cafe", null, null, null, 37.505, 127.000, null);
        Place placeOther = new Place("gp-other", "다른곳", "cafe", null, null, null, 37.506, 127.000, null);
        when(placeCatalogService.upsertAll(List.of(rawA, rawOther))).thenReturn(List.of(placeA, placeOther));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(user, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).recommendations())
                .extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("gp-other");
    }

    @Test
    void 타인_여행이면_빈값을_반환한다() {
        User owner = new User("google", "gap4-owner", "갭유저4주인", null);
        setId(owner, 1L);
        User other = new User("google", "gap4-other", "갭유저4남", null);
        setId(other, 2L);
        Trip trip = new Trip(owner, "여행", null, null);

        when(tripRepository.findById(1L)).thenReturn(Optional.of(trip));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(other, 1L, 1);

        assertThat(result).isEmpty();
    }
}
