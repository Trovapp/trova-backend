package com.trova.backend.repository;

import com.trova.backend.entity.ApiCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

/**
 * 같은 호출 기록을 N줄 묶음 INSERT로 남긴다(#79). 묶음 임베딩은 무료 한도를 문장 수로 세서 기록도 문장마다 한 줄이
 * 필요한데, JPA save를 N번 부르면 id가 IDENTITY라 Hibernate가 INSERT를 묶지 못하고 N번 왕복한다
 * (운영 실측: 18줄에 약 1.5초). 개발 DB 실측(로컬 Mac, 18줄): 각자 커밋 209ms → 묶음 전송 31ms.
 */
@Repository
public class ApiCallLogJdbcRepository {

    static final String INSERT_SQL = """
            INSERT INTO api_call_logs
                (provider, operation, job_id, latency_ms, success, error_message,
                 prompt_tokens, response_tokens, total_tokens, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public ApiCallLogJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertCopies(ApiCallLog log, int count) {
        if (count <= 0) {
            return;
        }
        Object[] row = {
                log.getProvider(), log.getOperation(), log.getJobId(), log.getLatencyMs(), log.isSuccess(),
                log.getErrorMessage(), log.getPromptTokens(), log.getResponseTokens(), log.getTotalTokens(),
                Timestamp.valueOf(log.getCreatedAt())
        };
        List<Object[]> args = Collections.nCopies(count, row);
        jdbcTemplate.batchUpdate(INSERT_SQL, args);
    }
}
