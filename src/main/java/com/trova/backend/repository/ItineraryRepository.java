package com.trova.backend.repository;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
    List<Itinerary> findByTripOrderByDay(Trip trip);
    Optional<Itinerary> findByTripAndDay(Trip trip, int day);

    // 날씨 스케줄러가 예보 범위(무료 티어 5일) 안의 일차만 스캔하려고 씀.
    List<Itinerary> findByDateBetween(LocalDate from, LocalDate to);
}
