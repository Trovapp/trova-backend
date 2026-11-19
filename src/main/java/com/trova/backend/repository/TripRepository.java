package com.trova.backend.repository;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    // 여행 상세(#476) — 소유자 확인을 조건으로 넣어 남의 여행과 없는 여행을 똑같이 빈 결과로 만든다(SQL 1회).
    @Query("select new com.trova.backend.repository.TripDetailHeader(t.id, t.title, t.startDate, t.endDate) "
            + "from Trip t where t.id = :id and t.user.id = :userId")
    Optional<TripDetailHeader> findDetailHeader(@Param("id") Long id, @Param("userId") Long userId);

    // 여행 상세(#476) — 빈 일차를 포함해 일차와 장소를 한 번에 읽는다. 정렬은 일차·방문 순서(이전 응답과 같음).
    @Query("select new com.trova.backend.repository.TripDetailRow("
            + "i.id, i.day, i.date, tp.id, tp.placeName, tp.region, tp.category, tp.latitude, tp.longitude, "
            + "tp.phone, tp.address, tp.visitOrder, tp.source, tp.googlePlaceId, "
            + "tp.visitStartTime, tp.visitEndTime, tp.arrivalTransportMode, tp.memo) "
            + "from Itinerary i left join TripPlace tp on tp.itinerary = i "
            + "where i.trip.id = :tripId order by i.day, i.id, tp.visitOrder, tp.id")
    List<TripDetailRow> findDetailRows(@Param("tripId") Long tripId);


    List<Trip> findByUserOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);
}
