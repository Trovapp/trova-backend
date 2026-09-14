package com.trova.backend.repository;

import com.trova.backend.entity.Place;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    // embedding은 pgvector 타입이라 Place 엔티티에 JPA 필드로 매핑하지 않는다 — H2
    // 테스트 DB가 vector 타입을 모르기 때문에(스키마 생성 시 전체 테스트 스위트가
    // 깨짐). 네이티브 쿼리로만 읽고 쓴다.

    @Query(value = "SELECT embedding::text FROM places WHERE id = :placeId AND embedding IS NOT NULL", nativeQuery = true)
    Optional<String> findEmbeddingText(@Param("placeId") Long placeId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE places SET embedding = CAST(:embeddingLiteral AS vector) WHERE id = :placeId", nativeQuery = true)
    void updateEmbedding(@Param("placeId") Long placeId, @Param("embeddingLiteral") String embeddingLiteral);

    @Query(value = "SELECT id FROM places WHERE id IN :ids AND embedding IS NOT NULL", nativeQuery = true)
    List<Long> findIdsWithEmbedding(@Param("ids") List<Long> ids);

    // 기존 카탈로그 백필용 — 검색 API가 아니라 관리자 엔드포인트에서만 쓴다.
    @Query(value = "SELECT id FROM places WHERE embedding IS NULL", nativeQuery = true)
    List<Long> findAllIdsWithoutEmbedding();
}
