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

    /** 후보 하나와 그 후보에 가장 가까운 신호 하나의 유사도(findCandidateSimilarities의 한 줄). */
    interface CandidateSimilarity {
        Long getCandidateId();
        double getSimilarity();
    }

    /**
     * findTopSimilarSignals를 후보마다 부르던 것을 한 번으로 묶는다(#482). 후보마다 같은 top-5(같은 거리 순서)를 골라
     * 후보 id·거리 순으로 돌려준다. 후보 임베딩은 이전과 같이 text로 바꿨다가 vector로 되돌려 비교한다
     * (findEmbeddingText → CAST(... AS vector)와 같은 값). 임베딩이 없는 후보·신호가 없는 사용자는 줄이 없다.
     */
    @Query(value = """
            SELECT c.id AS candidateId, top_signals.similarity AS similarity
            FROM places c
            CROSS JOIN LATERAL (
                SELECT 1 - (p.embedding <=> CAST(CAST(c.embedding AS text) AS vector)) AS similarity,
                       p.embedding <=> CAST(CAST(c.embedding AS text) AS vector) AS distance
                FROM user_preference_signals s
                JOIN places p ON s.place_id = p.id
                WHERE s.user_id = :userId AND p.embedding IS NOT NULL
                ORDER BY p.embedding <=> CAST(CAST(c.embedding AS text) AS vector)
                LIMIT 5
            ) top_signals
            WHERE c.id IN (:candidateIds) AND c.embedding IS NOT NULL
            ORDER BY c.id, top_signals.distance
            """, nativeQuery = true)
    List<CandidateSimilarity> findCandidateSimilarities(
            @Param("userId") Long userId, @Param("candidateIds") List<Long> candidateIds);
}
