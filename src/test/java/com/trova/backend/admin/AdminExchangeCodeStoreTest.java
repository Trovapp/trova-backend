package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExchangeCodeStoreTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T00:00:00Z"));
    private final AdminExchangeCodeStore store = new AdminExchangeCodeStore(clock);

    @Test
    void 코드는_한_번만_바꿀_수_있다() {
        String code = store.issue(7L);

        assertThat(store.consume(code)).contains(7L);
        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void 코드는_60초가_지나면_바꿀_수_없다() {
        String code = store.issue(7L);

        clock.advance(Duration.ofSeconds(61));

        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void 코드는_추측하기_어렵게_매번_다르고_32바이트다() {
        String a = store.issue(7L);
        String b = store.issue(7L);

        assertThat(a).isNotEqualTo(b);
        // URL에 넣는 base64url(패딩 없음) 32바이트 = 43자
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void 없는_코드나_빈_코드는_바꿀_수_없다() {
        assertThat(store.consume("nope")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
    }
}
