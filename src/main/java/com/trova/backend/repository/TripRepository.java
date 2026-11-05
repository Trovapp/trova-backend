package com.trova.backend.repository;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByUserOrderByCreatedAtDesc(User user);
    // 사용자 ID만으로 읽는 조회 API용(ADR 0036) — 사용자 행을 따로 읽지 않는다.
    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);
}
