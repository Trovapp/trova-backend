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

    @Test
    void 실제_후보가_있는_10개_타겟도_재귀_한도_안에서_전부_처리된다() throws Exception {
        // fix round 1 — Critical 이슈: buildGraph().compile()이 기본 recursionLimit(25)를
        // 쓰면 백트래킹/체크가 실제로 일어나는 10타겟 케이스가 25를 넘어
        // "Maximum number of iterations (25) reached!"로 예외를 던졌다(리뷰어가
        // langgraph4j 소스와 직접 실행으로 실측). 위의
        // 타겟이_10개_넘으면_최대_10개만_처리한다()는 후보가 전부 빈 리스트라
        // 타겟당 비용이 더 싸서 정확히 25에 걸려 우연히 통과했을 뿐, 실제
        // 후보 조회+충돌판정이 있는 10타겟 케이스를 증명하지 못했다. 이 테스트는
        // 10개 타겟 전부 실제 후보를 받고, 그중 하나는 반드시 백트래킹까지
        // 거치게 해서 TripReplanGraph가 RECURSION_LIMIT(70)를 올바르게 넘겨
        // 받는지 검증한다.
        Itinerary day1 = itinerary(1);
        List<TripPlace> places = new java.util.ArrayList<>();
        int visitOrder = 1;
        // 앵커(INDOOR, 타겟이 안 됨)로 각 타겟을 감싸서 타겟끼리 서로의 확정
        // 결과에 영향을 주지 않게 한다 — 모든 앵커가 같은 좌표(37.50,127.00)라
        // "가까운" 후보(약 10분)는 항상 충돌 없이 확정되고 "먼" 후보(약 40분)만
        // 충돌을 낸다(백트래킹 테스트에서 이미 검증한 수치 재사용).
        places.add(tripPlace(day1, 100L, 37.50, 127.00, "INDOOR", visitOrder++));
        for (long i = 1; i <= 10; i++) {
            places.add(tripPlace(day1, i, 37.50, 127.00, "OUTDOOR", visitOrder++));
            places.add(tripPlace(day1, 100L + i, 37.50, 127.00, "INDOOR", visitOrder++));
        }
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(places);

        AlternativeCandidate farCandidate = candidate(200L, 37.5240, 127.00);
        AlternativeCandidate nearCandidate = candidate(201L, 37.5060, 127.00);
        when(alternativeFinderService.findAlternatives(eq(user), eq(1L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(farCandidate, nearCandidate)));
        for (long i = 2; i <= 10; i++) {
            AlternativeCandidate onlyCandidate = candidate(300L + i, 37.5060, 127.00);
            when(alternativeFinderService.findAlternatives(eq(user), eq(i), any(AlternativeFilter.class)))
                    .thenReturn(Optional.of(List.of(onlyCandidate)));
        }

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        assertThat(outcome.matches()).hasSize(10);
        assertThat(outcome.matches().stream()
                .filter(m -> m.tripPlaceId().equals(1L))
                .findFirst().orElseThrow().candidate().placeId())
                .isEqualTo(201L); // target1은 1등 후보(200L)와 충돌해 2등(201L)으로 백트래킹
        verify(alternativeFinderService, times(10)).findAlternatives(any(), any(), any());
    }

    @Test
    void 날짜_경계를_넘는_이웃과는_충돌_판정을_하지_않는다() throws Exception {
        // fix round 1 — Important 이슈: run()이 여러 Itinerary(날짜)의 장소를 하나의
        // 리스트로 이어붙이는데, checkConflict는 리스트 인덱스만으로 prev/next를
        // 골라서 1일차 마지막 장소와 2일차 첫 장소를 서로 "이웃"으로 착각해
        // 충돌 판정을 할 수 있었다(스퓨리어스 실패). PlaceSnapshot에 dayId를
        // 추가하고 exceedsThreshold가 날짜가 다른 이웃을 건너뛰도록 고쳤다.
        Itinerary day1 = itinerary(1);
        Itinerary day2 = itinerary(2);
        TripPlace anchor1 = tripPlace(day1, 1L, 37.50, 127.00, "INDOOR", 1);
        TripPlace target = tripPlace(day1, 2L, 37.50, 127.00, "OUTDOOR", 2); // 1일차 마지막 장소
        // 2일차 첫 장소 — 타겟의 유일한 후보와 위도차 0.094도(약 156분)로 멀다.
        // 날짜 경계를 무시하면(버그) 이게 "다음 이웃"으로 취급돼 충돌 처리된다.
        TripPlace nextDayAnchor = tripPlace(day2, 3L, 37.60, 127.00, "INDOOR", 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1, day2));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(anchor1, target));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day2)).thenReturn(List.of(nextDayAnchor));

        // 후보를 1개만 준다 — 1일차 이전 장소(anchor1)와는 약 10분(충돌 없음).
        // 만약 2일차 첫 장소와의 크로스데이 비교가 남아있다면 이 유일한 후보가
        // 충돌로 판정되어 후보를 소진하고 실패로 떨어질 것이다.
        AlternativeCandidate onlyCandidate = candidate(21L, 37.5060, 127.00);
        when(alternativeFinderService.findAlternatives(eq(user), eq(2L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(onlyCandidate)));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.failedTripPlaceIds()).isEmpty();
        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).tripPlaceId()).isEqualTo(2L);
        assertThat(outcome.matches().get(0).candidate().placeId()).isEqualTo(21L);
    }

    @Test
    void 커스텀_직렬화기가_후보의_모든_필드를_보존한다() throws Exception {
        // fix round 1 — Important 이슈: 기존 6개 테스트는 전부 candidate().placeId()만
        // 비교해서, AlternativeCandidateSerializer 안에서 같은 타입인 두 필드의
        // 쓰기/읽기 순서가 뒤바뀌어도(예: googlePlaceId <-> name) 컴파일은 통과하고
        // placeId 비교만으로는 안 잡혔다. 14개 필드를 전부 채운 후보를 그래프에
        // 통과시켜(노드 실행마다 직렬화/역직렬화를 거침) 레코드 필드 단위 equals()로
        // 왕복 보존을 검증한다.
        Itinerary day1 = itinerary(1);
        TripPlace target = tripPlace(day1, 1L, 37.50, 127.00, "OUTDOOR", 1);
        when(itineraryRepository.findByTripOrderByDay(trip)).thenReturn(List.of(day1));
        when(tripPlaceRepository.findByItineraryOrderByVisitOrder(day1)).thenReturn(List.of(target));

        AlternativeCandidate fullCandidate = new AlternativeCandidate(
                42L, "google-place-xyz", "테스트 카페", "cafe", 4.7, 321,
                37.501, 127.001, "서울 강남구 어딘가", 0.35, 5,
                Boolean.TRUE, "VERY_CROWDED", "리뷰가 많고 평점이 높아요");
        when(alternativeFinderService.findAlternatives(eq(user), eq(1L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(fullCandidate)));

        TripReplanGraph.ReplanOutcome outcome = graph.run(user, trip, true);

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().get(0).candidate()).isEqualTo(fullCandidate);
    }
}
