package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TripPlaceRepository extends JpaRepository<TripPlace, Long> {
    List<TripPlace> findByItineraryOrderByVisitOrder(Itinerary itinerary);

    // 기본 EAGER 연관까지 함께 읽어 트랜잭션 밖에서도 일차별 추가 SELECT를 막는다.
    @Query("select tp from TripPlace tp join fetch tp.itinerary i join fetch i.trip t join fetch t.user "
            + "where t = :trip order by i.day, tp.visitOrder")
    List<TripPlace> findByTripOrderByDayAndVisitOrder(@Param("trip") Trip trip);

    // 빈 시간 추천(#482) — 소유자·일차 조건으로 일차와 장소를 한 번에 읽는다(SQL 1회). 남의 여행·없는 여행·없는 일차는
    // 빈 결과, 장소가 없는 일차는 placeId가 null인 한 줄이다. 정렬은 이전과 같은 방문 순서(동순위는 id로 고정).
    @Query("select new com.trova.backend.repository.GapPlaceRow("
            + "i.id, tp.id, tp.visitStartTime, tp.visitEndTime, tp.latitude, tp.longitude, tp.googlePlaceId) "
            + "from Itinerary i left join TripPlace tp on tp.itinerary = i "
            + "where i.trip.id = :tripId and i.trip.user.id = :userId and i.day = :day "
            + "order by i.id, tp.visitOrder, tp.id")
    List<GapPlaceRow> findGapPlaceRows(
            @Param("tripId") Long tripId, @Param("userId") Long userId, @Param("day") int day);

    long countByItinerary_Trip(Trip trip);

    // 여행 전체(모든 일차)의 장소 — 대안 후보에서 이미 여행에 있는 곳을 빼는 데 쓴다(#177).
    List<TripPlace> findByItinerary_Trip(Trip trip);

    // 여행 목록 요약(#123, #153) — 엔티티 대신 (여행 id, 지역)만 읽는다. TripPlace를 엔티티로 읽으면 기본 EAGER
    // 연관(일차 → 여행 → 사용자) 때문에 장소가 있는 일차마다 SELECT가 하나씩 더 나갔다(일차 10개에 13개, 테스트 실측).
    // "같은 수면 먼저 나온 지역" 기준을 저장 순서가 아니라 일차·방문 순서로 고정한다.
    @Query("select new com.trova.backend.repository.TripRegion(i.trip.id, tp.region) from TripPlace tp join tp.itinerary i "
            + "where i.trip in :trips order by i.trip.id, i.day, tp.visitOrder, tp.id")
    List<TripRegion> findRegionsByTripIn(@Param("trips") Collection<Trip> trips);

    // 같은 영상(ProcessingJob)의 SavedPlace가 이미 어떤 Trip으로 확정된 적이
    // 있는지 확인하는 용도 — confirm-trip 중복 제출 방지에 쓴다.
    Optional<TripPlace> findFirstBySavedPlaceIdIn(List<Long> savedPlaceIds);


    // 자동 초안 목록(#204) — 이 사용자의 여행에 들어간 영상 장소들의 원본 영상 주소만. 예전엔 사용자의 영상·저장 장소
    // 전체를 엔티티로 읽은 뒤 걸렀다(쿼리 3번 + 큰 칸들).
    @Query("select distinct sp.processingJob.sourceUrl from TripPlace tp, SavedPlace sp "
            + "where tp.savedPlaceId = sp.id and tp.itinerary.trip.user = :user and sp.user = :user")
    List<String> findSourceUrlsInTripsOf(@Param("user") User user);

    // 사용자 ID만으로 읽는 조회 API용(ADR 0036).
    @Query("select distinct sp.processingJob.sourceUrl from TripPlace tp, SavedPlace sp "
            + "where tp.savedPlaceId = sp.id and tp.itinerary.trip.user.id = :userId and sp.user.id = :userId")
    List<String> findSourceUrlsInTripsOfUserId(@Param("userId") Long userId);
}
