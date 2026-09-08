package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Notification;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserAndIsReadFalseOrderByCreatedAtDesc(User user);

    // 같은 Itinerary에 대해 중복 알림을 또 만들지 않기 위한 조회.
    Optional<Notification> findByItinerary(Itinerary itinerary);
}
