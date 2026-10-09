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

    // 여행 목록 요약(#123, #153) — 엔티티 대신 (여행 id, 지역)만 읽는다. TripPlace를 엔티티로 읽으면 기본 EAGER
    // 연관(일차 → 여행 → 사용자) 때문에 장소가 있는 일차마다 SELECT가 하나씩 더 나갔다(일차 10개에 13개, 테스트 실측).
    // "같은 수면 먼저 나온 지역" 기준을 저장 순서가 아니라 일차·방문 순서로 고정한다.
    @Query("select new com.trova.backend.repository.TripRegion(i.trip.id, tp.region) from TripPlace tp join tp.itinerary i "
            + "where i.trip in :trips order by i.trip.id, i.day, tp.visitOrder, tp.id")
    List<TripRegion> findRegionsByTripIn(@Param("trips") Collection<Trip> trips);

    // 같은 영상(ProcessingJob)의 SavedPlace가 이미 어떤 Trip으로 확정된 적이
    // 있는지 확인하는 용도 — confirm-trip 중복 제출 방지에 쓴다.
    Optional<TripPlace> findFirstBySavedPlaceIdIn(List<Long> savedPlaceIds);

    // 홈의 자동 초안 목록(#139) — 영상 장소 중 이미 이 사용자의 여행에 들어간 것을 한 번에 고른다.
    @Query("select distinct tp.savedPlaceId from TripPlace tp "
            + "where tp.savedPlaceId in :ids and tp.itinerary.trip.user = :user")
    List<Long> findSavedPlaceIdsInTripsOf(@Param("ids") Collection<Long> savedPlaceIds,
                                          @Param("user") User user);
}
