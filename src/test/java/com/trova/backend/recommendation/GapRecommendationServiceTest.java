package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.GapPlaceRow;
import com.trova.backend.repository.TripPlaceRepository;
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
