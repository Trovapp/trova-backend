package com.trova.backend.recommendation;

import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.entity.*;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlternativeFinderServiceTest {

    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private PlaceTaggingRunner placeTaggingRunner;
    @Mock private SeoulCongestionApiClient seoulCongestionApiClient;
    @Mock private ApiCallLogService apiCallLogService;
    @Mock private PlaceEmbeddingService placeEmbeddingService;
    @InjectMocks private AlternativeFinderService alternativeFinderService;

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User user() {
        User u = new User("google", "alt-user", "테스트", null);
        setId(u, 1L);
        return u;
    }

    private TripPlace tripPlace(Long id, User owner, Double lat, Double lng) {
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace place = new TripPlace(
                itinerary, "원래 장소", null, "cafe", lat, lng, null, null, 1, PlaceSource.NORMAL, null);
        setId(place, id);
        return place;
    }

    @Test
    void 존재하지_않는_장소면_빈값을_반환한다() {
        when(tripPlaceRepository.findById(99L)).thenReturn(Optional.empty());

        Optional<List<AlternativeCandidate>> result =
                alternativeFinderService.findAlternatives(user(), 99L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isEmpty();
    }

    @Test
    void 타인_소유_장소면_빈값을_반환한다() {
        User owner = user();
        User other = new User("google", "other", "남", null);
        setId(other, 2L);
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));

        Optional<List<AlternativeCandidate>> result =
                alternativeFinderService.findAlternatives(other, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isEmpty();
    }

    @Test
    void 필터_없으면_검색_결과를_그대로_후보로_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-1", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.3, 50, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "서울 어딘가");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place upserted = new Place("gp-1", "대안카페", "cafe", 4.3, 50, "PRICE_LEVEL_MODERATE", 37.501, 127.001, "서울 어딘가");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(upserted));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).name()).isEqualTo("대안카페");
        assertThat(result.get().get(0).googlePlaceId()).isEqualTo("gp-1");
    }

    @Test
    void 실내만_필터하면_공간태그가_INDOOR인_후보만_남는다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var rawIndoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-in", new GooglePlacesNearbySearchResponse.Place.DisplayName("실내카페"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        var rawOutdoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-out", new GooglePlacesNearbySearchResponse.Place.DisplayName("야외공원"),
                List.of("park"), 4.5, 20, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawIndoor, rawOutdoor)));

        Place indoorPlace = new Place("gp-in", "실내카페", "cafe", 4.0, 10, null, 37.501, 127.001, "주소1");
        Place outdoorPlace = new Place("gp-out", "야외공원", "park", 4.5, 20, null, 37.502, 127.002, "주소2");
        when(placeCatalogService.upsertAll(List.of(rawIndoor, rawOutdoor)))
                .thenReturn(List.of(indoorPlace, outdoorPlace));

        when(placeTaggingRunner.run(anyList(), anyLong())).thenReturn(List.of(
                new PlaceTag(0, "조용한", "INDOOR"), new PlaceTag(1, "활기찬", "OUTDOOR")));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, true, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::name).containsExactly("실내카페");
    }

    @Test
    void 다음_장소가_있으면_거리를_계산하고_maxDistanceKm으로_필터한다() {
        User owner = user();
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace place = new TripPlace(itinerary, "원래", null, "cafe", 37.500, 127.000, null, null, 1, PlaceSource.NORMAL, null);
        TripPlace next = new TripPlace(itinerary, "다음", null, "cafe", 37.510, 127.000, null, null, 2, PlaceSource.NORMAL, null);
        setId(place, 1L);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary)).thenReturn(List.of(place, next));

        // 위도 0.01도 ≈ 1.11km — 후보를 next 바로 옆(약 1.1km)과 아주 먼 곳(약 5.5km) 두 개로 구성
        var near = new GooglePlacesNearbySearchResponse.Place(
                "gp-near", new GooglePlacesNearbySearchResponse.Place.DisplayName("가까운곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.511, 127.000), null);
        var far = new GooglePlacesNearbySearchResponse.Place(
                "gp-far", new GooglePlacesNearbySearchResponse.Place.DisplayName("먼곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.560, 127.000), null);
        when(googlePlacesApiClient.searchNearby(37.500, 127.000, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(near, far)));

        Place nearPlace = new Place("gp-near", "가까운곳", "cafe", null, null, null, 37.511, 127.000, null);
        Place farPlace = new Place("gp-far", "먼곳", "cafe", null, null, null, 37.560, 127.000, null);
        when(placeCatalogService.upsertAll(List.of(near, far))).thenReturn(List.of(nearPlace, farPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, 2.0, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::name).containsExactly("가까운곳");
    }

    @Test
    void 구글_검색이_실패하면_500_대신_빈_목록을_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenThrow(new RuntimeException("Google Places API 장애"));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void 태깅_서브프로세스가_실패해도_500_대신_필터된_결과를_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-tag-fail", new GooglePlacesNearbySearchResponse.Place.DisplayName("태깅안된카페"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place untaggedPlace = new Place("gp-tag-fail", "태깅안된카페", "cafe", 4.0, 10, null, 37.501, 127.001, "주소1");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(untaggedPlace));

        // PlaceTaggingRunner.run이 429/타임아웃/스크립트 부재 등으로 PipelineException을 던지는
        // 상황을 흉내낸다 — 대안 찾기 전체가 500으로 죽으면 안 되고, space=null(미태깅) 상태로
        // 그냥 걸러져야 한다(indoorOnly=true라 INDOOR 아니면 제외).
        when(placeTaggingRunner.run(anyList(), anyLong()))
                .thenThrow(new RuntimeException("Gemini 태깅 실패(429)"));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, true, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void 좌표가_없는_후보는_결과에서_제외된다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var rawNoCoord = new GooglePlacesNearbySearchResponse.Place(
                "gp-no-coord", new GooglePlacesNearbySearchResponse.Place.DisplayName("좌표없는곳"),
                List.of("cafe"), null, null, null, null, null);
        var rawWithCoord = new GooglePlacesNearbySearchResponse.Place(
                "gp-with-coord", new GooglePlacesNearbySearchResponse.Place.DisplayName("좌표있는곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawNoCoord, rawWithCoord)));

        // Google 응답에 location이 없어 Place.latitude/longitude가 null로 캐시된 경우
        // (PlaceCatalogService.upsertAll이 실제로 만들 수 있는 상태) — 이게 upsertAll의 캐시
        // 히트로도 재현되므로, 여기서는 이미 좌표 없이 저장된 Place를 직접 흉내낸다.
        Place noCoordPlace = new Place("gp-no-coord", "좌표없는곳", "cafe", null, null, null, null, null, null);
        Place withCoordPlace = new Place("gp-with-coord", "좌표있는곳", "cafe", null, null, null, 37.502, 127.002, "주소2");
        when(placeCatalogService.upsertAll(List.of(rawNoCoord, rawWithCoord)))
                .thenReturn(List.of(noCoordPlace, withCoordPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("gp-with-coord");
    }

    @Test
    void 교체_대상_자기_자신은_후보에서_제외된다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        place.applyGooglePlaceId("gp-self");
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary())).thenReturn(List.of(place));

        var rawSelf = new GooglePlacesNearbySearchResponse.Place(
                "gp-self", new GooglePlacesNearbySearchResponse.Place.DisplayName("원래 장소"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "주소");
        var rawOther = new GooglePlacesNearbySearchResponse.Place(
                "gp-other", new GooglePlacesNearbySearchResponse.Place.DisplayName("다른 카페"),
                List.of("cafe"), 4.2, 5, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawSelf, rawOther)));

        Place selfPlace = new Place("gp-self", "원래 장소", "cafe", 4.0, 10, null, 37.5, 127.0, "주소");
        Place otherPlace = new Place("gp-other", "다른 카페", "cafe", 4.2, 5, null, 37.501, 127.001, "주소2");
        when(placeCatalogService.upsertAll(List.of(rawSelf, rawOther))).thenReturn(List.of(selfPlace, otherPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("gp-other");
    }
}
