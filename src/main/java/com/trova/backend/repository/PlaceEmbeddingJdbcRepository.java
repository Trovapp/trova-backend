package com.trova.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 여러 장소의 임베딩을 묶음 전송(JDBC batch) 한 번으로 저장한다(#79).
 * PlaceRepository.updateEmbedding을 장소마다 부르면 요청마다 트랜잭션이 열리고 DB를 따로 왕복해서,
 * 운영 서버(도쿄 → 서울 DB)에서 18곳에 1263ms가 걸렸다. 개발 DB 실측(로컬 Mac, 18곳): 각자 커밋 297ms → 묶음 전송 54ms.
 */
@Repository
public class PlaceEmbeddingJdbcRepository {

    static final String UPDATE_SQL = "UPDATE places SET embedding = CAST(? AS vector) WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public PlaceEmbeddingJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 키는 장소 id, 값은 pgvector 리터럴("[0.1,0.2,...]"). 순서를 지키려면 LinkedHashMap을 넘긴다. */
    public void updateEmbeddings(Map<Long, String> embeddingLiteralsByPlaceId) {
        if (embeddingLiteralsByPlaceId.isEmpty()) {
            return;
        }
        List<Object[]> args = embeddingLiteralsByPlaceId.entrySet().stream()
                .map(e -> new Object[]{e.getValue(), e.getKey()})
                .toList();
        jdbcTemplate.batchUpdate(UPDATE_SQL, args);
    }
}
