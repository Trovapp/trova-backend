package com.trova.backend.replan;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripReplanGraphTest {

    @Mock private AlternativeFinderService alternativeFinderService;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;

    private TripReplanGraph graph;
    private User user;
    private Trip trip;

    @BeforeEach
    void setUp() throws Exception {
        graph = new TripReplanGraph(alternativeFinderService, itineraryRepository, tripPlaceRepository);
        user = new User("google", "u1", "테스트유저", null);
        setId(user, 1L);
        trip = new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        setId(trip, 10L);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private Itinerary itinerary(int day) throws Exception {
        Itinerary itinerary = new Itinerary(trip, day, LocalDate.of(2026, 1, day));
        setId(itinerary, (long) (100 + day));
        return itinerary;
    }

    private TripPlace tripPlace(Itinerary itinerary, Long id, double lat, double lng, String space, int visitOrder) throws Exception {
        TripPlace place = new TripPlace(itinerary, "장소" + id, "서울", "cafe", lat, lng, null, null, visitOrder, PlaceSource.NORMAL, null);
        place.applySpace(space);
        setId(place, id);
        return place;
    }

    private AlternativeCandidate candidate(Long placeId, double lat, double lng) {
        return new AlternativeCandidate(placeId, "g-" + placeId, "대안" + placeId, "cafe", 4.5, 100,
                lat, lng, "서울", null, null, false, null, null);
    }

    @Test
    void 후보가_이웃과_충돌하면_다음_순위_후보로_백트래킹한다() throws Exception {
        Itinerary day1 = itinerary(1);
        // 이웃(장소1, 37.50/127.00, INDOOR) - 타겟(장소2, 37.50/127.00, OUTDOOR) 순서.
        // 타겟 하나뿐이라 이웃은 장소1(이전)만 있고 다음은 없음.
        TripPlace neighbor = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        TripPlace target = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(neighbor, target));

        // 1등 후보는 이웃과 40분 거리(도보 4km/h 기준 약 2.67km, 위도 1도≈111km로
        // 환산해 위도차 0.024도로 역산) — 충돌(30분 초과).
        // 2등 후보는 이웃과 약 10분 거리(약 0.67km, 위도차 0.006도) — 충돌 없음.
        AlternativeCandidate farCandidate = candidate(20L, 37.5240, 127.00);
        AlternativeCandidate nearCandidate = candidate(21L, 37.5060, 127.00);
        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(farCandidate, nearCandidate)));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).tripPlaceId()).isEqualTo(2L);
        assertThat(outcome.matches().get(0).candidate().placeId()).isEqualTo(21L);
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        // 백트래킹 중에도 후보 조회는 타겟당 정확히 1회만 — 재시도할 때 API를 또
        // 부르지 않는다는 비용 설계를 고정한다.
        verify(alternativeFinderService, times(1)).findAlternatives(eq(user), eq(2L), any());
    }

    @Test
    void 모든_후보가_충돌하면_실패목록에_들어가고_원래장소는_그대로다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace neighbor = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        TripPlace target = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(neighbor, target));

        // 3개 후보 전부 이웃과 40분 이상 거리.
        List<AlternativeCandidate> allFar = List.of(
                candidate(20L, 37.5240, 127.00),
                candidate(21L, 37.5240, 127.01),
                candidate(22L, 37.5240, 127.02));
        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(allFar));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).containsExactly(2L);
        verify(alternativeFinderService, times(1)).findAlternatives(eq(user), eq(2L), any());
    }

    @Test
    void 한_타겟이_실패해도_나머지_타겟은_계속_처리된다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace target1 = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 1);
        TripPlace target2 = tripPlace(day1, 3L, 37.60, 127.10, "OUTDOOR", 2);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(target1, target2));

        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of())); // target1: 후보 자체가 없음(즉시 실패, 좌표는 원래 그대로 남음)
        // target2의 유일한 이웃은 target1(실패해서 원래 좌표 37.50/127.00 그대로) —
        // 그 근처(37.501/127.001, 약 150m)로 후보를 잡아 충돌 없이 확정되게 한다.
        when(alternativeFinderService.findAlternatives(eq(user), eq(3L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(candidate(30L, 37.501, 127.001))));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.failedTripPlaceIds()).containsExactly(2L);
        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).tripPlaceId()).isEqualTo(3L);
    }

    @Test
    void 실내인_장소는_indoorOnly_요청에서_타겟이_되지_않는다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace indoorPlace = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(indoorPlace));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        verifyNoInteractions(alternativeFinderService);
    }

    @Test
    void space가_null인_장소는_보수적으로_제외된다() throws Exception {
        Itinerary day1 = itinerary(1);
        TripPlace untagged = tripPlace(day1, 1L, 37.50, 127.00, null, 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(untagged));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).isEmpty();
        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        verifyNoInteractions(alternativeFinderService);
    }

    @Test
    void 타겟이_10개_넘으면_최대_10개만_처리한다() throws Exception {
        Itinerary day1 = itinerary(1);
        List<TripPlace> places = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            places.add(tripPlace(day1, (long) i, 37.50 + i * 0.001, 127.00, "OUTDOOR", i));
        }
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(places);
        when(alternativeFinderService.findAlternatives(any(), any(), any()))
                .thenReturn(Optional.of(List.of()));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches().size() + outcome.failedTripPlaceIds().size()).isEqualTo(10);
        verify(alternativeFinderService, times(10)).findAlternatives(any(), any(), any());
    }
}
