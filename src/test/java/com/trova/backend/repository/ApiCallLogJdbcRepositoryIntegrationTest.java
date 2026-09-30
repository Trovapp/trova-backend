package com.trova.backend.repository;

import com.trova.backend.entity.ApiCallLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 묶음 INSERT의 컬럼 이름·값이 JPA 엔티티(ApiCallLog)와 맞는지 실제 테이블(H2)에 넣어 확인한다(#79).
 * 관리자 통계는 이 테이블을 JPA로 읽으므로, 여기서 넣은 줄이 엔티티로 그대로 읽혀야 한다.
 */
@SpringBootTest
@Transactional
class ApiCallLogJdbcRepositoryIntegrationTest {

    @Autowired private ApiCallLogJdbcRepository apiCallLogJdbcRepository;
    @Autowired private ApiCallLogRepository apiCallLogRepository;

    @Test
    void 같은_기록을_N줄_넣고_엔티티로_읽힌다() {
        long before = apiCallLogRepository.count();

        apiCallLogJdbcRepository.insertCopies(new ApiCallLog(
                "gemini", "place-embedding-batch", null, 1234L, false, "embedding generation failed",
                null, null, null), 3);

        List<ApiCallLog> saved = apiCallLogRepository.findAll().stream()
                .filter(l -> "place-embedding-batch".equals(l.getOperation()))
                .toList();
        assertThat(apiCallLogRepository.count()).isEqualTo(before + 3);
        assertThat(saved).hasSize(3).allSatisfy(l -> {
            assertThat(l.getProvider()).isEqualTo("gemini");
            assertThat(l.getLatencyMs()).isEqualTo(1234L);
            assertThat(l.isSuccess()).isFalse();
            assertThat(l.getErrorMessage()).isEqualTo("embedding generation failed");
            assertThat(l.getJobId()).isNull();
            assertThat(l.getCreatedAt()).isNotNull();
        });
    }
}
