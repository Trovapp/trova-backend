package com.trova.backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    @EntityGraph(attributePaths = {"user", "members.user"})
    Optional<Trip> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id=:id")
    Optional<Trip> lockById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id=(select l.trip.id from TripShareLink l where l.token=:token and l.revokedAt is null)")
    Optional<Trip> lockForToken(String token);

    // 여행 상세(#476)는 멤버 조건과 버전을 함께 읽는다.
    @Query("select new com.trova.backend.repository.TripDetailHeader(t.id, t.title, t.startDate, t.endDate, t.version) "
            + "from Trip t where t.id = :id and exists (select 1 from t.members m where m.user.id = :userId)")
    Optional<TripDetailHeader> findDetailHeader(@Param("id") Long id, @Param("userId") Long userId);

    // 여행 상세(#476) — 빈 일차를 포함해 일차와 장소를 한 번에 읽는다. 정렬은 일차·방문 순서(이전 응답과 같음).
    @Query("select new com.trova.backend.repository.TripDetailRow("
            + "i.id, i.day, i.date, tp.id, tp.placeName, tp.region, tp.category, tp.latitude, tp.longitude, "
            + "tp.phone, tp.address, tp.visitOrder, tp.source, tp.googlePlaceId, "
            + "tp.visitStartTime, tp.visitEndTime, tp.arrivalTransportMode, tp.memo, tp.version) "
            + "from Itinerary i left join TripPlace tp on tp.itinerary = i "
            + "where i.trip.id = :tripId order by i.day, i.id, tp.visitOrder, tp.id")
    List<TripDetailRow> findDetailRows(@Param("tripId") Long tripId);


    List<Trip> findByUserOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    @Query("select distinct t from Trip t left join fetch t.members where exists (select 1 from t.members m where m.user.id=:userId) order by t.createdAt desc")
    List<Trip> findTripsForMember(Long userId);
}
