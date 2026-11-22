package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.GapPlaceRow;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GapRecommendationServiceTest {

    @Mock private SavedPlaceRepository savedPlaceRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private GooglePlacesApiClient googlePlacesApiClient;
    @Mock private PlaceCatalogService placeCatalogService;
    @Mock private ApiCallLogService apiCallLogService;
    @Mock private PlaceEmbeddingService placeEmbeddingService;
    @Mock private PersonalizationService personalizationService;
    @InjectMocks private GapRecommendationService gapRecommendationService;

    private static final Long USER_ID = 1L;
    private static final Long ITINERARY_ID = 50L;

    private static GapPlaceRow row(Long placeId, String start, String end, Double lat, Double lng, String googlePlaceId) {
        return new GapPlaceRow(ITINERARY_ID, placeId, start == null ? null : LocalTime.parse(start),
                end == null ? null : LocalTime.parse(end), lat, lng, googlePlaceId);
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static GooglePlacesNearbySearchResponse.Place raw(String id) {
        return new GooglePlacesNearbySearchResponse.Place(
                id, new GooglePlacesNearbySearchResponse.Place.DisplayName(id),
                List.of("cafe"), null, null, null,
                new GooglePlacesNearbySearchResponse.Place.Location(37.505, 127.000), null);
    }

    private static Place place(Long id, String googlePlaceId, String category, Double rating, Integer reviews) {
        Place place = new Place(googlePlaceId, googlePlaceId, category, rating, reviews, null, 37.505, 127.000, null);
        setId(place, id);
        return place;
    }

    @ParameterizedTest
    @CsvSource({
            "e편한세상 봉황역아파트, establishment, false", "강일병원, establishment, false",
            "반계초등학교, establishment, false", "김해시청, establishment, false",
            "KCC 문막공장, establishment, false", "쌍용자동차 서비스 프라자, establishment, false",
            "마가의다락방교회, place_of_worship, false", "어승생 공설묘지, cemetery, false",
            "굴포천역, establishment, false", "무명시설, doctor, false",
            "유엔기념공원, cemetery|park, true", "불국사, place_of_worship|tourist_attraction, true",
            "강일병원, cafe, true", "봄의원, korean_restaurant, true", "봄대학교, university|school, true",
            "봄편의점, convenience_store, true", "봄호텔, hotel|lodging, true"
    })
    void 빈시간_후보의_전체_유형과_이름을_검사한다(String name, String types, boolean keep) {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(
                List.of(row(10L, null, "10:00", 37.5, 127.0, null)));
        var raw = new GooglePlacesNearbySearchResponse.Place("candidate",
                new GooglePlacesNearbySearchResponse.Place.DisplayName(name),
                List.of(("point_of_interest|" + types).split("[|]")), null, null, null, null, null);
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        when(placeCatalogService.upsertAll(anyList())).thenReturn(List.of(
                place(1L, "candidate", "point_of_interest", null, null)));

        var recommendations = gapRecommendationService.findGaps(USER_ID, 1L, 1).orElseThrow().getFirst().recommendations();
        assertThat(recommendations).hasSize(keep ? 1 : 0);
    }

    @ParameterizedTest
    @CsvSource({
            "의료, false", "관광, true", "음식, true"
    })
    void 저장된_카카오_분류도_같은_ID에만_적용한다(String kind, boolean keep) {
        String path = switch (kind) {
            case "의료" -> "의료,건강 > 병원 > 내과";
            case "관광" -> "여행 > 관광,명소 > 문화유적";
            default -> "음식점 > 카페";
        };
        var raw = new GooglePlacesNearbySearchResponse.Place("candidate",
                new GooglePlacesNearbySearchResponse.Place.DisplayName("의료".equals(kind) ? "새봄" : "강일병원"),
                List.of("establishment"), null, null, null, null, null);
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(
                List.of(row(10L, null, "10:00", 37.5, 127.0, null)));
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        when(placeCatalogService.upsertAll(anyList())).thenReturn(List.of(place(1L, "candidate", null, null, null)));
        when(savedPlaceRepository.findKakaoCategories(USER_ID, List.of("candidate")))
                .thenReturn(List.of(new SavedPlaceRepository.KakaoCategoryRow("candidate", path)));

        assertThat(gapRecommendationService.findGaps(USER_ID, 1L, 1).orElseThrow().getFirst().recommendations())
                .hasSize(keep ? 1 : 0);
    }

    @Test
    void 시간이_없는_장소_쌍은_gap으로_잡지_않는다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, null, null, 37.5, 127.0, null),
                row(11L, null, null, 37.6, 127.1, null)));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
        verifyNoInteractions(googlePlacesApiClient);
    }

    @Test
    void _30분_넘게_비면_gap으로_잡고_중간지점을_검색한다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, null, "10:00", 37.500, 127.000, null),
                row(11L, "11:00", null, 37.510, 127.000, null)));
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), eq(1500.0), isNull()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).beforePlaceId()).isEqualTo(10L);
        assertThat(result.get().get(0).afterPlaceId()).isEqualTo(11L);
        assertThat(result.get().get(0).gapMinutes()).isEqualTo(60);
    }

    @Test
    void _30분_이하로_비면_gap으로_안_잡는다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, null, "10:00", 37.500, 127.000, null),
                row(11L, "10:20", null, 37.510, 127.000, null)));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void 이전_이후_장소_자기_자신은_추천에서_제외된다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, null, "10:00", 37.500, 127.000, "gp-a"),
                row(11L, "11:00", null, 37.510, 127.000, "gp-b")));
        GooglePlacesNearbySearchResponse.Place rawA = raw("gp-a");
        GooglePlacesNearbySearchResponse.Place rawOther = raw("gp-other");
        // 중간지점(37.500/37.510 평균)은 부동소수점 연산 결과라 리터럴 37.505와 비트가
        // 정확히 일치하지 않을 수 있다 — anyDouble()로 매칭한다.
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(rawA, rawOther)));
        Place placeA = place(100L, "gp-a", "cafe", null, null);
        Place placeOther = place(101L, "gp-other", "cafe", null, null);
        when(placeCatalogService.upsertAll(List.of(rawA, rawOther))).thenReturn(List.of(placeA, placeOther));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).recommendations())
                .extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("gp-other");
        // 개인화 점수는 걸러진 후보 전체에 대해 한 번만 묻는다.
        verify(personalizationService).personalizationScores(USER_ID, List.of(placeOther));
    }

    @Test
    void 개인화_점수는_기존_공식대로_곱해_정렬하고_맵에_없는_후보는_0점이다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, "08:00", "09:00", 37.5, 127.0, null)));
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw("a"), raw("b"), raw("c"), raw("d"))));
        // 기본 점수: a = 4*ln(11) ≈ 9.59, b = 바닥값 1.0, c = 바닥값 1.0, d = 3*ln(101) ≈ 13.85
        Place a = place(1L, "a", "cafe", 4.0, 10);
        Place b = place(2L, "b", "cafe", null, null);
        Place c = place(3L, "c", "cafe", 1.0, 0);
        Place d = place(4L, "d", "cafe", 3.0, 100);
        when(placeCatalogService.upsertAll(anyList())).thenReturn(List.of(a, b, c, d));
        // a: 9.59*(1+1*0.6)=15.35 > d: 13.85(점수 없음) > c: 1.0*(1+0.5*0.6)=1.3 > b: 1.0
        when(personalizationService.personalizationScores(USER_ID, List.of(a, b, c, d)))
                .thenReturn(Map.of(1L, 1.0, 3L, 0.5));

        List<GapRecommendationService.Gap> gaps = gapRecommendationService.findGaps(USER_ID, 1L, 1).orElseThrow();

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).recommendations()).extracting(AlternativeCandidate::googlePlaceId)
                .containsExactly("a", "d", "c", "b");
    }

    @Test
    void NaN_후보는_유한_점수_뒤로_가고_유한_점수와_동점_순서는_유지된다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, "08:00", "09:00", 37.5, 127.0, null)));
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw("nan"), raw("low"), raw("high"), raw("tie"))));
        List<Place> candidates = List.of(place(1L, "nan", "cafe", null, null),
                place(2L, "low", "cafe", null, null), place(3L, "high", "cafe", null, null),
                place(4L, "tie", "cafe", null, null));
        when(placeCatalogService.upsertAll(anyList())).thenReturn(candidates);
        when(personalizationService.personalizationScores(USER_ID, candidates))
                .thenReturn(Map.of(1L, Double.NaN, 2L, -0.5, 3L, 0.8, 4L, -0.5));

        assertThat(gapRecommendationService.findGaps(USER_ID, 1L, 1).orElseThrow().get(0).recommendations())
                .extracting(AlternativeCandidate::googlePlaceId).containsExactly("high", "low", "tie", "nan");
    }

    @Test
    void 남의_여행_없는_여행_없는_일차는_조회_결과가_없어_빈값이다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of());

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).isEmpty();
        verifyNoInteractions(googlePlacesApiClient, personalizationService);
    }

    @Test
    void 장소가_없는_일차는_빈_목록이다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1))
                .thenReturn(List.of(row(null, null, null, null, null, null)));

        Optional<List<GapRecommendationService.Gap>> result = gapRecommendationService.findGaps(USER_ID, 1L, 1);

        assertThat(result).contains(List.of());
    }

    @Test
    void 같은_일차가_둘이면_이전처럼_하나를_고르지_않고_실패한다() {
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of(
                row(10L, "08:00", "09:00", 37.5, 127.0, null),
                new GapPlaceRow(51L, 12L, null, null, 37.5, 127.0, null)));

        assertThatThrownBy(() -> gapRecommendationService.findGaps(USER_ID, 1L, 1))
                .isInstanceOf(org.springframework.dao.IncorrectResultSizeDataAccessException.class);
        verify(googlePlacesApiClient, never()).searchNearby(anyDouble(), anyDouble(), anyDouble(), any());
    }

    @Test
    void 사용자_엔티티로_불러도_ID로_같은_조회를_한다() {
        User user = new User("google", "gap-user", "갭유저", null);
        setId(user, USER_ID);
        when(tripPlaceRepository.findGapPlaceRows(1L, USER_ID, 1)).thenReturn(List.of());

        assertThat(gapRecommendationService.findGaps(user, 1L, 1)).isEmpty();
        verify(tripPlaceRepository).findGapPlaceRows(1L, USER_ID, 1);
    }
}
