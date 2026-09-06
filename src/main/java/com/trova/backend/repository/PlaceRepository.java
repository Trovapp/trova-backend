package com.trova.backend.repository;

import com.trova.backend.entity.Place;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    // reviewSnippets(@ElementCollection, LAZY)를 findById 시점에 같이 조회한다.
    // PlaceReviewService가 이 결과를 짧게 읽고 바로 반환한 뒤 트랜잭션이 끝나므로,
    // 여기서 미리 fetch해두지 않으면(open-in-view: false 환경에서) 호출 측이
    // detached 엔티티의 reviewSnippets를 읽는 순간 LazyInitializationException이 난다.
    @Override
    @EntityGraph(attributePaths = "reviewSnippets")
    Optional<Place> findById(Long id);

    Optional<Place> findByGooglePlaceId(String googlePlaceId);

    // N+1 방지 — Plan B의 실측 부하테스트에서 후보를 하나씩 조회하다 걸렸던 문제라
    // 처음부터 배치 조회로 만든다.
    List<Place> findByGooglePlaceIdIn(List<String> googlePlaceIds);
}
