package com.trova.backend.repository;

import com.trova.backend.entity.Place;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public class PlaceReviewUpdatesImpl implements PlaceReviewUpdates {
    private final EntityManager entityManager;

    public PlaceReviewUpdatesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void updateReviewCache(Place place) {
        entityManager.createQuery("""
                update Place p set p.reviewSummary = :summary, p.reviewSummaryGeneratedAt = :generatedAt,
                    p.phone = :phone, p.phoneCheckedAt = :checkedAt where p.id = :id
                """)
                .setParameter("summary", place.getReviewSummary())
                .setParameter("generatedAt", place.getReviewSummaryGeneratedAt())
                .setParameter("phone", place.getPhone())
                .setParameter("checkedAt", place.getPhoneCheckedAt())
                .setParameter("id", place.getId())
                .executeUpdate();
        // ElementCollection도 같은 짧은 트랜잭션에서 교체하고, detached 엔티티는 merge하지 않는다.
        entityManager.createNativeQuery("delete from place_review_snippets where place_id = :id")
                .setParameter("id", place.getId()).executeUpdate();
        List<String> snippets = place.getReviewSnippets();
        for (int i = 0; i < snippets.size(); i++) {
            entityManager.createNativeQuery("""
                    insert into place_review_snippets (place_id, snippet_order, snippet)
                    values (:id, :snippetOrder, :snippet)
                    """)
                    .setParameter("id", place.getId())
                    .setParameter("snippetOrder", i)
                    .setParameter("snippet", snippets.get(i))
                    .executeUpdate();
        }
    }

    @Override
    @Transactional
    public void updatePhone(Place place) {
        entityManager.createQuery("""
                update Place p set p.phone = :phone, p.phoneCheckedAt = :checkedAt where p.id = :id
                """)
                .setParameter("phone", place.getPhone())
                .setParameter("checkedAt", place.getPhoneCheckedAt())
                .setParameter("id", place.getId())
                .executeUpdate();
    }
}
