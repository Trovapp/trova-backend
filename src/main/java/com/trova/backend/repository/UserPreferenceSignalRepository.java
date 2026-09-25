package com.trova.backend.repository;

import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreferenceSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPreferenceSignalRepository extends JpaRepository<UserPreferenceSignal, Long> {
    void deleteByUser(User user);


    /** pgvector 코사인 유사도로 이 사용자의 과거 신호 중 후보 임베딩과 가장 비슷한 top-5를 찾는다. */
    interface SimilarSignal {
        String getName();
        String getCategory();
        String getMood();
        double getSimilarity();
    }

    @Query(value = """
            SELECT p.name AS name, p.category AS category, p.mood AS mood,
                   1 - (p.embedding <=> CAST(:candidateEmbeddingText AS vector)) AS similarity
            FROM user_preference_signals s
            JOIN places p ON s.place_id = p.id
            WHERE s.user_id = :userId AND p.embedding IS NOT NULL
            ORDER BY p.embedding <=> CAST(:candidateEmbeddingText AS vector)
            LIMIT 5
            """, nativeQuery = true)
    List<SimilarSignal> findTopSimilarSignals(
            @Param("userId") Long userId, @Param("candidateEmbeddingText") String candidateEmbeddingText);
}
