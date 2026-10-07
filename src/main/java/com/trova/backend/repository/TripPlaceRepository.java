package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.TripPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripPlaceRepository extends JpaRepository<TripPlace, Long> {
    List<TripPlace> findByItineraryOrderByVisitOrder(Itinerary itinerary);

    // 여행 목록 요약(#123) — 여행마다 따로 부르지 않고 한 번에 가져온다.
    List<TripPlace> findByItineraryTripIn(java.util.Collection<com.trova.backend.entity.Trip> trips);

    // 같은 영상(ProcessingJob)의 SavedPlace가 이미 어떤 Trip으로 확정된 적이
    // 있는지 확인하는 용도 — confirm-trip 중복 제출 방지에 쓴다.
    Optional<TripPlace> findFirstBySavedPlaceIdIn(List<Long> savedPlaceIds);

    // 홈의 자동 초안 목록(#139) — 영상 장소 중 이미 이 사용자의 여행에 들어간 것을 한 번에 고른다.
    @org.springframework.data.jpa.repository.Query("select distinct tp.savedPlaceId from TripPlace tp "
            + "where tp.savedPlaceId in :ids and tp.itinerary.trip.user = :user")
    List<Long> findSavedPlaceIdsInTripsOf(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> savedPlaceIds,
                                          @org.springframework.data.repository.query.Param("user") com.trova.backend.entity.User user);
}
