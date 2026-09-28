package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    void deleteByUser(User user);

    List<Notification> findByUserAndIsReadFalseOrderByCreatedAtDesc(User user);

    // 같은 Itinerary에 대해 중복 알림을 또 만들지 않기 위한 조회.
    Optional<Notification> findByItinerary(Itinerary itinerary);

    // 알림이 가리키는 장소를 교체·삭제할 때 같이 정리하기 위한 조회(trip_place_id는 외래키가 없다, #41).
    List<Notification> findByTripPlaceId(Long tripPlaceId);
}
