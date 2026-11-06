package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    void deleteByUser(User user);

    List<Notification> findByUserAndIsReadFalseOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    // 일정·여행·사용자를 한 쿼리로 같이 읽는다 — 알림마다 일정을 따로 읽던 N+1을 없앤다(#335).
    @EntityGraph(attributePaths = {"user", "itinerary", "itinerary.trip", "itinerary.trip.user"})
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);

    // 같은 Itinerary에 대해 중복 알림을 또 만들지 않기 위한 조회.
    Optional<Notification> findByItinerary(Itinerary itinerary);

    // 알림이 가리키는 장소를 교체·삭제할 때 같이 정리하기 위한 조회(trip_place_id는 외래키가 없다, #41).
    List<Notification> findByTripPlaceId(Long tripPlaceId);

    // 여행 날짜를 바꾸면 예전 날짜 기준 비 알림을 지운다(#181).
    List<Notification> findByItinerary_Trip(Trip trip);
}
