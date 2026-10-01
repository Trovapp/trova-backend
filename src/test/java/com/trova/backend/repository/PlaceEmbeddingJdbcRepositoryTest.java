package com.trova.backend.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 임베딩 컬럼은 pgvector 타입이라 H2 테스트 DB에서는 실제 UPDATE를 돌릴 수 없다.
 * 여기서는 "장소 N개를 묶음 전송 한 번으로 보낸다"는 호출 형태만 검사하고, 실제 SQL은 개발 DB(Postgres)에서 확인한다(#79).
 */
class PlaceEmbeddingJdbcRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void 여러_장소의_임베딩을_묶음_전송_한_번으로_저장한다() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlaceEmbeddingJdbcRepository repository = new PlaceEmbeddingJdbcRepository(jdbcTemplate);
        Map<Long, String> literals = new LinkedHashMap<>();
        literals.put(11L, "[1.0]");
        literals.put(12L, "[0.5]");

        repository.updateEmbeddings(literals);

        ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, times(1)).batchUpdate(
                eq("UPDATE places SET embedding = CAST(? AS vector) WHERE id = ?"), args.capture());
        assertThat(args.getValue()).containsExactly(new Object[]{"[1.0]", 11L}, new Object[]{"[0.5]", 12L});
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 저장할_장소가_없으면_DB를_부르지_않는다() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        new PlaceEmbeddingJdbcRepository(jdbcTemplate).updateEmbeddings(Map.of());

        verifyNoInteractions(jdbcTemplate);
    }
}
