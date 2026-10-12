package com.trova.backend.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T00:00:10Z"));
    private final RequestRateLimiter limiter = new RequestRateLimiter(clock);

    @Test
    void 분당_한도까지만_허용하고_다음_분에_다시_허용한다() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("ip:1.2.3.4", 3)).isTrue();
        }
        assertThat(limiter.tryAcquire("ip:1.2.3.4", 3)).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("ip:1.2.3.4", 3)).isTrue();
    }

    @Test
    void 키마다_따로_센다() {
        assertThat(limiter.tryAcquire("ip:1.1.1.1", 1)).isTrue();
        assertThat(limiter.tryAcquire("ip:1.1.1.1", 1)).isFalse();
        assertThat(limiter.tryAcquire("ip:2.2.2.2", 1)).isTrue();
    }
}
