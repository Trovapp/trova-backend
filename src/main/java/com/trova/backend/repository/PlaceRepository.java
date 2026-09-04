package com.trova.backend.repository;

import com.trova.backend.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    Optional<Place> findByGooglePlaceId(String googlePlaceId);

    // N+1 방지 — Plan B의 실측 부하테스트에서 후보를 하나씩 조회하다 걸렸던 문제라
    // 처음부터 배치 조회로 만든다.
    List<Place> findByGooglePlaceIdIn(List<String> googlePlaceIds);
}
