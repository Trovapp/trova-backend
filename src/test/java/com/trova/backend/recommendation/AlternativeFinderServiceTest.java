package com.trova.backend.recommendation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlternativeFinderServiceTest {

    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private PlaceTaggingRunner placeTaggingRunner;
    @Mock private SeoulCongestionApiClient seoulCongestionApiClient;
    @Mock private ApiCallLogService apiCallLogService;
    @Mock private PlaceEmbeddingService placeEmbeddingService;
    @Mock private PersonalizationService personalizationService;
    @Mock private PlaceRepository placeRepository;
    @Spy private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks private AlternativeFinderService alternativeFinderService;

    @BeforeEach
    void 보충_검색의_기본_응답은_빈_목록이다() {
        lenient().when(placeEmbeddingService.ensureEmbeddingsWithWork(anyList(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return new PlaceEmbeddingService.EmbeddingStats(0, 0);
        });
        lenient().when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));
    }

    @Test
    void 기본_모드_이유는_검색_필터가_아닌_원본과_후보_종류를_비교한다() {
        User owner = prepareTarget("museum");
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("park", "park"), rawPlace("museum", "museum"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "park"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);
        List<AlternativeCandidate> actual = alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter("공원", null, null, null, null), false).orElseThrow();
        assertEquals("원래 장소에서 직선 142m · 평점 4", actual.get(0).recommendationReason());
        assertEquals("원래 장소에서 직선 142m · 같은 종류의 박물관이에요", actual.get(1).recommendationReason());
    }

    @Test
    void 복구_모드는_상위_두곳에만_Gemini_이유를_붙인다() {
        ReflectionTestUtils.setField(alternativeFinderService, "geminiExplanationEnabled", true);
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("first", "cafe"),
                rawPlace("second", "cafe"), rawPlace("third", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);
        when(personalizationService.retrieveAndScore(eq(owner), any()))
                .thenReturn(new PersonalizationService.PersonalizationResult(0, List.of()));
        when(personalizationService.explainFromSignals(any(), any())).thenAnswer(invocation ->
                Optional.of(((Place) invocation.getArgument(0)).getGooglePlaceId() + " Gemini 이유"));
        List<AlternativeCandidate> actual = alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter(null, null, null, null, null)).orElseThrow();
        assertThat(actual).extracting(AlternativeCandidate::recommendationReason)
                .containsExactly("first Gemini 이유", "second Gemini 이유", null);
        verify(personalizationService, times(2)).explainFromSignals(any(), eq(List.of()));
        verify(personalizationService, times(3)).retrieveAndScore(eq(owner), any());
        verify(personalizationService, never()).personalizationScores(anyLong(), anyList());
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
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
    void 기본_모드는_묶음_점수로_정렬하고_Gemini를_부르지_않는다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("low", "cafe"),
                rawPlace("high", "cafe"), rawPlace("tie", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        List<Place> candidates = raw.stream().map(p -> {
            Place c = new Place(p.id(), p.displayName().text(), "cafe", 4.0, 10, null, 37.501, 127.001, "주소");
            setId(c, (long) raw.indexOf(p) + 10);
            return c;
        }).toList();
        when(placeCatalogService.upsertAll(raw)).thenReturn(candidates);
        when(personalizationService.personalizationScores(owner.getId(), candidates))
                .thenReturn(Map.of(10L, 0.0, 11L, 0.8, 12L, 0.0));
        List<AlternativeCandidate> actual = alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter(null, null, null, null, null)).orElseThrow();
        assertThat(actual).extracting(AlternativeCandidate::googlePlaceId).containsExactly("high", "low", "tie");
        assertEquals("원래 장소에서 직선 142m · 같은 종류의 카페예요", actual.get(0).recommendationReason());
        assertEquals("원래 장소에서 직선 142m · 같은 종류의 카페예요", actual.get(1).recommendationReason());
        assertThat(actual.get(2).recommendationReason()).isNull();
        verify(personalizationService, never()).retrieveAndScore(any(), any());
        verify(personalizationService, never()).explainFromSignals(any(), any());
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

        org.assertj.core.api.Assertions.assertThatExceptionOfType(
                    org.springframework.web.server.ResponseStatusException.class)
                    .isThrownBy(() -> alternativeFinderService.findAlternatives(other, 1L, new AlternativeFilter(null, null, null, null, null)))
                    .satisfies(error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void 카페_원본은_필터가_없으면_카페로_검색한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-1", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.3, 50, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "서울 어딘가");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));

        Place upserted = new Place("gp-1", "대안카페", "cafe", 4.3, 50, "PRICE_LEVEL_MODERATE", 37.501, 127.001, "서울 어딘가");
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(upserted));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).name()).isEqualTo("대안카페");
        assertThat(result.get().get(0).googlePlaceId()).isEqualTo("gp-1");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "cafe");
    }

    @Test
    void 실내만_필터하면_공간태그가_INDOOR인_후보만_남는다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawIndoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-in", new GooglePlacesNearbySearchResponse.Place.DisplayName("실내카페"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        GooglePlacesNearbySearchResponse.Place rawOutdoor = new GooglePlacesNearbySearchResponse.Place(
                "gp-out", new GooglePlacesNearbySearchResponse.Place.DisplayName("야외공원"),
                List.of("park"), 4.5, 20, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
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
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(itinerary.getTrip())).thenReturn(List.of(place, next));

        // 위도 0.01도 ≈ 1.11km — 후보를 next 바로 옆(약 1.1km)과 아주 먼 곳(약 5.5km) 두 개로 구성
        GooglePlacesNearbySearchResponse.Place near = new GooglePlacesNearbySearchResponse.Place(
                "gp-near", new GooglePlacesNearbySearchResponse.Place.DisplayName("가까운곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.511, 127.000), null);
        GooglePlacesNearbySearchResponse.Place far = new GooglePlacesNearbySearchResponse.Place(
                "gp-far", new GooglePlacesNearbySearchResponse.Place.DisplayName("먼곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.560, 127.000), null);
        when(googlePlacesApiClient.searchNearby(37.500, 127.000, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(near, far)));

        Place nearPlace = new Place("gp-near", "가까운곳", "cafe", null, null, null, 37.511, 127.000, null);
        Place farPlace = new Place("gp-far", "먼곳", "cafe", null, null, null, 37.560, 127.000, null);
        when(placeCatalogService.upsertAll(List.of(near, far))).thenReturn(List.of(nearPlace, farPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, 2.0, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::name).containsExactly("가까운곳");
        assertEquals("원래 장소에서 직선 1.2km · 같은 종류의 카페예요",
                result.orElseThrow().get(0).recommendationReason());
    }

    @Test
    void 일차_검색이_실패하면_빈_목록을_반환하고_보충_검색하지_않는다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenThrow(new RuntimeException("Google Places API 장애"));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
        verify(googlePlacesApiClient, never()).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 태깅_서브프로세스가_실패해도_500_대신_필터된_결과를_반환한다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-tag-fail", new GooglePlacesNearbySearchResponse.Place.DisplayName("태깅안된카페"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
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
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawNoCoord = new GooglePlacesNearbySearchResponse.Place(
                "gp-no-coord", new GooglePlacesNearbySearchResponse.Place.DisplayName("좌표없는곳"),
                List.of("cafe"), null, null, null, null, null);
        GooglePlacesNearbySearchResponse.Place rawWithCoord = new GooglePlacesNearbySearchResponse.Place(
                "gp-with-coord", new GooglePlacesNearbySearchResponse.Place.DisplayName("좌표있는곳"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
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
    void 같은_여행에_이미_있는_장소는_후보에서_제외된다() {
        // 진관사계곡 대안으로 같은 여행 1번 은평한옥마을이 나왔다(2026-10-13 PWA QA, #177).
        // 영상에서 온 장소는 구글 장소 ID가 없어 이름으로, 검색으로 담은 장소는 ID로 거른다.
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        TripPlace sameTripByName = new TripPlace(
                place.getItinerary(), "은평 한옥마을", null, "attraction", 37.6, 126.9, null, null, 2, PlaceSource.NORMAL, null);
        TripPlace sameTripById = new TripPlace(
                place.getItinerary(), "검색으로 담은 곳", null, "cafe", 37.6, 126.9, null, null, 3, PlaceSource.NORMAL, null);
        sameTripById.applyGooglePlaceId("gp-in-trip");
        // 구글 이름엔 종단명이 붙는다("대한불교조계종 진관사") — 이름이 그 장소 이름으로 끝나고 가까우면 같은 곳
        TripPlace temple = new TripPlace(
                place.getItinerary(), "진관사", null, "attraction", 37.5040, 127.0, null, null, 4, PlaceSource.NORMAL, null);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip()))
                .thenReturn(List.of(place, sameTripByName, sameTripById, temple));

        GooglePlacesNearbySearchResponse.Place rawByName = new GooglePlacesNearbySearchResponse.Place(
                "gp-village", new GooglePlacesNearbySearchResponse.Place.DisplayName("은평한옥마을"),
                List.of("tourist_attraction"), 4.3, 100, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.0), "주소1");
        GooglePlacesNearbySearchResponse.Place rawById = new GooglePlacesNearbySearchResponse.Place(
                "gp-in-trip", new GooglePlacesNearbySearchResponse.Place.DisplayName("다른 이름"),
                List.of("cafe"), 4.1, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.0), "주소2");
        GooglePlacesNearbySearchResponse.Place rawOther = new GooglePlacesNearbySearchResponse.Place(
                "gp-other", new GooglePlacesNearbySearchResponse.Place.DisplayName("새로운 곳"),
                List.of("cafe"), 4.2, 5, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.503, 127.0), "주소3");
        GooglePlacesNearbySearchResponse.Place rawTemple = new GooglePlacesNearbySearchResponse.Place(
                "gp-temple", new GooglePlacesNearbySearchResponse.Place.DisplayName("대한불교조계종 진관사"),
                List.of("place_of_worship"), 4.6, 300, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.5045, 127.0), "주소4");
        // 이름에 여행 장소 이름이 들어 있어도 끝나지 않으면(지점) 다른 가게라 남는다
        GooglePlacesNearbySearchResponse.Place rawBranch = new GooglePlacesNearbySearchResponse.Place(
                "gp-branch", new GooglePlacesNearbySearchResponse.Place.DisplayName("청류 진관사점"),
                List.of("cafe"), 4.0, 20, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.5041, 127.0), "주소5");
        List<GooglePlacesNearbySearchResponse.Place> raws = List.of(rawByName, rawById, rawOther, rawTemple, rawBranch);
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raws));
        when(placeCatalogService.upsertAll(raws)).thenReturn(List.of(
                new Place("gp-village", "은평한옥마을", "attraction", 4.3, 100, null, 37.501, 127.0, "주소1"),
                new Place("gp-in-trip", "다른 이름", "cafe", 4.1, 10, null, 37.502, 127.0, "주소2"),
                new Place("gp-other", "새로운 곳", "cafe", 4.2, 5, null, 37.503, 127.0, "주소3"),
                new Place("gp-temple", "대한불교조계종 진관사", "attraction", 4.6, 300, null, 37.5045, 127.0, "주소4"),
                new Place("gp-branch", "청류 진관사점", "cafe", 4.0, 20, null, 37.5041, 127.0, "주소5")));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId)
                .containsExactlyInAnyOrder("gp-other", "gp-branch");
    }

    @Test
    void 필터_없이_찾으면_숙소와_역은_후보에서_빠진다() {
        // 10:20 식당 자리의 대안이 호텔 6곳·지하철역이었다(수정 확인 QA 2026-11-12, #416).
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawHotel = new GooglePlacesNearbySearchResponse.Place(
                "gp-hotel", new GooglePlacesNearbySearchResponse.Place.DisplayName("어느 호텔"),
                List.of("hotel"), 4.4, 5000, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.0), "주소1");
        GooglePlacesNearbySearchResponse.Place rawStation = new GooglePlacesNearbySearchResponse.Place(
                "gp-station", new GooglePlacesNearbySearchResponse.Place.DisplayName("어느 역"),
                List.of("subway_station"), 4.0, 900, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.0), "주소2");
        GooglePlacesNearbySearchResponse.Place rawCafe = new GooglePlacesNearbySearchResponse.Place(
                "gp-cafe", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.2, 50, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.503, 127.0), "주소3");
        List<GooglePlacesNearbySearchResponse.Place> raws = List.of(rawHotel, rawStation, rawCafe);
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raws));
        when(placeCatalogService.upsertAll(raws)).thenReturn(List.of(
                new Place("gp-hotel", "어느 호텔", "hotel", 4.4, 5000, null, 37.501, 127.0, "주소1"),
                new Place("gp-station", "어느 역", "subway_station", 4.0, 900, null, 37.502, 127.0, "주소2"),
                new Place("gp-cafe", "대안카페", "cafe", 4.2, 50, null, 37.503, 127.0, "주소3")));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("gp-cafe");
    }

    @Test
    void 식당_자리는_필터가_없어도_식당에서_대안을_찾는다() {
        User owner = user();
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace place = new TripPlace(
                itinerary, "돼지국밥집", null, "korean_restaurant", 37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
        setId(place, 1L);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        Itinerary tomorrow = new Itinerary(trip, 2, null);
        setId(itinerary, 31L);
        setId(tomorrow, 32L);
        TripPlace firstTomorrow = new TripPlace(tomorrow, "다음 날 첫 장소", null, "park", 35.0, 127.0,
                null, null, 1, PlaceSource.NORMAL, null);
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(trip)).thenReturn(List.of(place, firstTomorrow));

        GooglePlacesNearbySearchResponse.Place raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-r", new GooglePlacesNearbySearchResponse.Place.DisplayName("다른 국밥집"),
                List.of("korean_restaurant"), 4.3, 50, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "restaurant"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        when(placeCatalogService.upsertAll(List.of(raw))).thenReturn(List.of(
                new Place("gp-r", "다른 국밥집", "korean_restaurant", 4.3, 50, null, 37.501, 127.001, "주소")));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("gp-r");
        assertThat(result.orElseThrow().getFirst().distanceToNextKm()).isNull();
    }

    @Test
    void 교체_대상_자기_자신은_후보에서_제외된다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        place.applyGooglePlaceId("gp-self");
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawSelf = new GooglePlacesNearbySearchResponse.Place(
                "gp-self", new GooglePlacesNearbySearchResponse.Place.DisplayName("원래 장소"),
                List.of("cafe"), 4.0, 10, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "주소");
        GooglePlacesNearbySearchResponse.Place rawOther = new GooglePlacesNearbySearchResponse.Place(
                "gp-other", new GooglePlacesNearbySearchResponse.Place.DisplayName("다른 카페"),
                List.of("cafe"), 4.2, 5, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawSelf, rawOther)));

        Place selfPlace = new Place("gp-self", "원래 장소", "cafe", 4.0, 10, null, 37.5, 127.0, "주소");
        Place otherPlace = new Place("gp-other", "다른 카페", "cafe", 4.2, 5, null, 37.501, 127.001, "주소2");
        when(placeCatalogService.upsertAll(List.of(rawSelf, rawOther))).thenReturn(List.of(selfPlace, otherPlace));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("gp-other");
    }

    @Test
    void 개인화_점수가_높은_후보가_먼저_온다() {
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawLow = new GooglePlacesNearbySearchResponse.Place(
                "low-gp", new GooglePlacesNearbySearchResponse.Place.DisplayName("낮은 후보"),
                List.of("cafe"), 3.0, 1, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        GooglePlacesNearbySearchResponse.Place rawHigh = new GooglePlacesNearbySearchResponse.Place(
                "high-gp", new GooglePlacesNearbySearchResponse.Place.DisplayName("높은 후보"),
                List.of("cafe"), 3.0, 1, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawLow, rawHigh)));

        // rating/reviewCount를 동일하게 맞춰서 PlaceScoring.baseScore가 같도록 하고,
        // personalizationService만으로 순위가 갈리는지 검증한다.
        Place low = new Place("low-gp", "낮은 후보", "cafe", 3.0, 1, null, 37.501, 127.001, "주소1");
        Place high = new Place("high-gp", "높은 후보", "cafe", 3.0, 1, null, 37.502, 127.002, "주소2");
        setId(low, 10L);
        setId(high, 11L);
        when(placeCatalogService.upsertAll(List.of(rawLow, rawHigh))).thenReturn(List.of(low, high));
        when(personalizationService.personalizationScores(owner.getId(), List.of(low, high)))
                .thenReturn(Map.of(10L, 0.0, 11L, 1.0));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get().get(0).name()).isEqualTo("높은 후보");
    }

    @Test
    void 평점_리뷰수_없어_baseScore가_0이어도_개인화_점수로_순위가_갈린다() {
        // 곱셈 부스트(baseScore * (1 + score*weight))라 baseScore가 0이면 개인화가
        // 아무리 높아도 최종 점수가 0으로 묻힐 위험이 있다 — MIN_BASE_SCORE_FOR_BOOST
        // 바닥값으로 이 경우에도 개인화가 실제로 순위에 반영되는지 검증한다.
        User owner = user();
        TripPlace place = tripPlace(1L, owner, 37.5, 127.0);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(place));
        when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(place.getItinerary().getTrip())).thenReturn(List.of(place));

        GooglePlacesNearbySearchResponse.Place rawLow = new GooglePlacesNearbySearchResponse.Place(
                "low-gp", new GooglePlacesNearbySearchResponse.Place.DisplayName("낮은 후보"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소1");
        GooglePlacesNearbySearchResponse.Place rawHigh = new GooglePlacesNearbySearchResponse.Place(
                "high-gp", new GooglePlacesNearbySearchResponse.Place.DisplayName("높은 후보"),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.502, 127.002), "주소2");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawLow, rawHigh)));

        Place low = new Place("low-gp", "낮은 후보", "cafe", null, null, null, 37.501, 127.001, "주소1");
        Place high = new Place("high-gp", "높은 후보", "cafe", null, null, null, 37.502, 127.002, "주소2");
        setId(low, 10L);
        setId(high, 11L);
        when(placeCatalogService.upsertAll(List.of(rawLow, rawHigh))).thenReturn(List.of(low, high));
        when(personalizationService.personalizationScores(owner.getId(), List.of(low, high)))
                .thenReturn(Map.of(10L, 0.0, 11L, 1.0));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result).isPresent();
        assertThat(result.get().get(0).name()).isEqualTo("높은 후보");
    }

    @Test
    void NaN_대안은_유한_점수_뒤로_가고_유한_점수와_동점_순서는_유지된다() {
        ReflectionTestUtils.setField(alternativeFinderService, "geminiExplanationEnabled", true);
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("nan", "cafe"),
                rawPlace("low", "cafe"), rawPlace("high", "cafe"), rawPlace("tie", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);
        when(personalizationService.retrieveAndScore(any(), any())).thenAnswer(invocation -> {
            Place candidate = invocation.getArgument(1);
            double score = switch (candidate.getGooglePlaceId()) {
                case "nan" -> Double.NaN;
                case "high" -> 0.8;
                default -> -0.5;
            };
            return new PersonalizationService.PersonalizationResult(score, List.of());
        });

        assertThat(alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter(null, null, null, null, null)).orElseThrow())
                .extracting(AlternativeCandidate::googlePlaceId).containsExactly("high", "low", "tie", "nan");
    }

    @Test
    void NaN인_기본_검색_후보보다_유한한_보충_후보가_앞선다() {
        ReflectionTestUtils.setField(alternativeFinderService, "geminiExplanationEnabled", true);
        User owner = prepareTarget();
        GooglePlacesNearbySearchResponse.Place nan = rawPlace("nan", "cafe");
        GooglePlacesNearbySearchResponse.Place primary = rawPlace("primary", "cafe");
        GooglePlacesNearbySearchResponse.Place supplement = rawPlace("supplement", "park");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(nan, primary)));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(supplement)));
        prepareCandidates(List.of(nan, primary, supplement));
        when(personalizationService.retrieveAndScore(any(), any())).thenAnswer(invocation -> {
            Place candidate = invocation.getArgument(1);
            double score = switch (candidate.getGooglePlaceId()) {
                case "nan" -> Double.NaN;
                case "primary" -> -0.5;
                default -> 0.8;
            };
            return new PersonalizationService.PersonalizationResult(score, List.of());
        });

        assertThat(alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter(null, null, null, null, null)).orElseThrow())
                .extracting(AlternativeCandidate::googlePlaceId).containsExactly("primary", "supplement", "nan");
    }

    private GooglePlacesNearbySearchResponse.Place rawPlace(String id, String category) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(id), List.of(category),
                4.0, 10, null, new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소");
    }

    private User prepareTarget() {
        return prepareTarget("cafe");
    }

    private User prepareTarget(String category) {
        User owner = user();
        Trip trip = new Trip(owner, "여행", null, null);
        Itinerary itinerary = new Itinerary(trip, 1, null);
        TripPlace target = new TripPlace(itinerary, "원래 장소", null, category,
                37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null);
        setId(target, 1L);
        when(tripPlaceRepository.findById(1L)).thenReturn(Optional.of(target));
        lenient().when(tripPlaceRepository.findByTripOrderByDayAndVisitOrder(target.getItinerary().getTrip())).thenReturn(List.of(target));
        return owner;
    }

    private void prepareCandidates(List<GooglePlacesNearbySearchResponse.Place> raw) {
        List<Place> places = raw.stream().map(p -> {
            Place place = new Place(p.id(), p.displayName().text(), p.types().get(0),
                    p.rating(), p.userRatingCount(), null, 37.501, 127.001, "주소");
            setId(place, (long) raw.indexOf(p) + 10);
            return place;
        }).toList();
        when(placeCatalogService.upsertAll(raw)).thenReturn(places);
    }

    @Test
    void 같은_종류가_두곳이면_종류_없이_한번_보충하고_중복을_빼고_뒤에_붙인다() {
        User owner = prepareTarget();
        GooglePlacesNearbySearchResponse.Place first = rawPlace("카페1", "cafe");
        GooglePlacesNearbySearchResponse.Place second = rawPlace("카페2", "cafe");
        GooglePlacesNearbySearchResponse.Place extra = new GooglePlacesNearbySearchResponse.Place(
                "인기공원", new GooglePlacesNearbySearchResponse.Place.DisplayName("인기공원"), List.of("park"),
                5.0, 10000, null, new GooglePlacesNearbySearchResponse.Place.Location(37.501, 127.001), "주소");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(first, second)));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(extra, second, extra)));
        prepareCandidates(List.of(first, second, extra));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("카페1", "카페2", "인기공원");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "cafe");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
        verifyNoMoreInteractions(googlePlacesApiClient);
    }

    @Test
    void 같은_종류가_세곳_이상이면_보충_검색하지_않는다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(
                rawPlace("카페1", "cafe"), rawPlace("카페2", "cafe"), rawPlace("카페3", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).hasSize(3);
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "cafe");
        verify(googlePlacesApiClient, never()).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 같은_종류가_없어도_보충_검색으로_후보를_찾는다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("공원", "park"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(null));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("공원");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 보충_검색이_실패해도_같은_종류의_후보를_유지한다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("카페", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenThrow(new RuntimeException("보충 검색 실패"));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("카페");
        verify(apiCallLogService).record(
                eq("google-places"), eq("nearby-search-alternative"), eq(null), anyLong(),
                eq(false), eq("보충 검색 실패"), eq(null), eq(null), eq(null));
    }

    @Test
    void 매핑할_종류가_없으면_종류_없이_한번만_검색한다() {
        User owner = prepareTarget("unknown");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).isEmpty();
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
        verifyNoMoreInteractions(googlePlacesApiClient);
    }

    @Test
    void 명시한_분류_필터는_원본_종류보다_우선하고_두곳이어도_보충하지_않는다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(
                rawPlace("박물관1", "museum"), rawPlace("박물관2", "museum"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "museum"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter("박물관", null, null, null, null));

        assertThat(result.orElseThrow()).hasSize(2);
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "museum");
        verify(googlePlacesApiClient, never()).searchNearby(37.5, 127.0, 2000, "cafe");
        verify(googlePlacesApiClient, never()).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 숙소_하위분류의_원본도_숙소_대안을_유지한다() {
        User owner = prepareTarget("boutique_hotel");
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("호텔", "lodging"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "lodging"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("호텔");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "lodging");
    }

    @Test
    void 원본_분류가_null이면_종류_없이_한번_검색하고_숙소와_역을_제외한다() {
        User owner = prepareTarget(null);
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(
                rawPlace("공원", "park"), rawPlace("숙소", "lodging"), rawPlace("역", "train_station"),
                rawPlace("분류없는곳", "unknown"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        Place park = new Place("공원", "공원", "park", 4.0, 10, null, 37.501, 127.001, "주소");
        Place lodging = new Place("숙소", "숙소", "lodging", 4.0, 10, null, 37.501, 127.001, "주소");
        Place station = new Place("역", "역", "train_station", 4.0, 10, null, 37.501, 127.001, "주소");
        Place uncategorized = new Place("분류없는곳", "분류없는곳", null, 4.0, 10, null, 37.501, 127.001, "주소");
        setId(park, 10L);
        setId(uncategorized, 11L);
        when(placeCatalogService.upsertAll(raw)).thenReturn(List.of(park, lodging, station, uncategorized));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("공원", "분류없는곳");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
        verifyNoMoreInteractions(googlePlacesApiClient);
    }

    @Test
    void 아침_카페_대안의_보충_응답에서_한식당을_제외한다() {
        User owner = prepareTarget();
        TripPlace target = tripPlaceRepository.findById(1L).orElseThrow();
        target.restoreSchedule(1, LocalTime.of(9, 0), LocalTime.of(10, 0));
        GooglePlacesNearbySearchResponse.Place cafe = rawPlace("카페", "cafe");
        GooglePlacesNearbySearchResponse.Place restaurant = rawPlace("한식당", "korean_restaurant");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(cafe)));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(restaurant)));
        prepareCandidates(List.of(cafe, restaurant));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId).containsExactly("카페");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 원본_자신이_포함된_세곳이면_보충_검색한다() {
        User owner = prepareTarget();
        TripPlace target = tripPlaceRepository.findById(1L).orElseThrow();
        target.applyGooglePlaceId("원본");
        List<GooglePlacesNearbySearchResponse.Place> primary = List.of(
                rawPlace("원본", "cafe"), rawPlace("카페1", "cafe"), rawPlace("카페2", "cafe"));
        GooglePlacesNearbySearchResponse.Place extra = rawPlace("공원", "park");
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(primary));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, null))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(extra)));
        prepareCandidates(List.of(primary.get(0), primary.get(1), primary.get(2), extra));

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null));

        assertThat(result.orElseThrow()).extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("카페1", "카페2", "공원");
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, null);
    }

    @Test
    void 보충을_허용하지_않으면_두곳이어도_보충_검색하지_않는다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(
                rawPlace("카페1", "cafe"), rawPlace("카페2", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        prepareCandidates(raw);

        Optional<List<AlternativeCandidate>> result = alternativeFinderService.findAlternatives(
                owner, 1L, new AlternativeFilter(null, null, null, null, null), false);

        assertThat(result.orElseThrow()).hasSize(2);
        verify(googlePlacesApiClient).searchNearby(37.5, 127.0, 2000, "cafe");
        verify(googlePlacesApiClient, never()).searchNearby(37.5, 127.0, 2000, null);
        verifyNoMoreInteractions(googlePlacesApiClient);
    }

    @Test
    void 유효_만료_없는_태그가_섞이면_필요한_후보만_태깅하고_저장한다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(
                rawPlace("fresh", "cafe"), rawPlace("expired", "cafe"), rawPlace("missing", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        List<Place> candidates = tagCandidates(raw);
        candidates.get(0).applySpaceTag("INDOOR");
        candidates.get(1).applySpaceTag("OUTDOOR");
        ReflectionTestUtils.setField(candidates.get(1), "spaceTaggedAt", LocalDateTime.now().minusDays(31));
        when(placeCatalogService.upsertAll(raw)).thenReturn(candidates);
        when(placeTaggingRunner.run(anyList(), anyLong())).thenReturn(List.of(
                new PlaceTag(0, "조용한", "INDOOR"), new PlaceTag(1, "조용한", "OUTDOOR")));
        Logger logger = (Logger) LoggerFactory.getLogger(AlternativeFinderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(alternativeFinderService.findAlternatives(owner, 1L,
                    new AlternativeFilter(null, true, null, null, null), false).orElseThrow())
                    .extracting(AlternativeCandidate::googlePlaceId).containsExactly("fresh", "expired");
            ArgumentCaptor<List<PlaceTaggingRunner.TagCandidate>> captor = ArgumentCaptor.forClass(List.class);
            verify(placeTaggingRunner).run(captor.capture(), anyLong());
            assertThat(captor.getValue()).extracting(PlaceTaggingRunner.TagCandidate::index).containsExactly(0, 1);
            assertThat(captor.getValue()).extracting(PlaceTaggingRunner.TagCandidate::name)
                    .containsExactly(candidates.get(1).getName(), candidates.get(2).getName());
            verify(placeRepository, never()).updateSpaceTag(eq(10L), any(), any());
            verify(placeRepository).updateSpaceTag(eq(11L), eq("INDOOR"), any());
            verify(placeRepository).updateSpaceTag(eq(12L), eq("OUTDOOR"), any());
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("재사용 1·신규 2"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 첫_후보_태그_저장이_실패해도_나머지_후보를_저장하고_응답한다() {
        User owner = prepareTarget();
        List<GooglePlacesNearbySearchResponse.Place> raw = List.of(rawPlace("failed", "cafe"), rawPlace("next", "cafe"));
        when(googlePlacesApiClient.searchNearby(37.5, 127.0, 2000, "cafe"))
                .thenReturn(new GooglePlacesNearbySearchResponse(raw));
        List<Place> candidates = tagCandidates(raw);
        when(placeCatalogService.upsertAll(raw)).thenReturn(candidates);
        when(placeTaggingRunner.run(anyList(), anyLong())).thenReturn(List.of(
                new PlaceTag(0, "조용한", "INDOOR"), new PlaceTag(1, "조용한", "INDOOR")));
        when(placeRepository.updateSpaceTag(eq(10L), any(), any())).thenThrow(new IllegalStateException("DB 실패"));
        assertThat(alternativeFinderService.findAlternatives(owner, 1L,
                new AlternativeFilter(null, true, null, null, null), false).orElseThrow())
                .extracting(AlternativeCandidate::googlePlaceId).containsExactly("failed", "next");
        verify(placeRepository).updateSpaceTag(eq(11L), eq("INDOOR"), any());
    }

    private List<Place> tagCandidates(List<GooglePlacesNearbySearchResponse.Place> raw) {
        return raw.stream().map(item -> {
            Place place = new Place(item.id(), item.id(), "cafe", 4.0, 10, null, 37.501, 127.001, "주소");
            setId(place, (long) raw.indexOf(item) + 10);
            return place;
        }).toList();
    }

}
