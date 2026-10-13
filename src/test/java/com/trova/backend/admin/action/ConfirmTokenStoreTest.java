package com.trova.backend.admin.action;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmTokenStoreTest {

    private static final ConfirmTokenStore.Pending PENDING =
            new ConfirmTokenStore.Pending(1L, AdminAction.DELETE_TRIP, 55L, 900L, "제주 여행");

    @Test
    void 한_번만_꺼낼_수_있다() {
        ConfirmTokenStore store = new ConfirmTokenStore(Clock.systemDefaultZone());
        String token = store.issue(PENDING);

        assertThat(store.consume(token)).contains(PENDING);
        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    void 만료_5분이_지나면_꺼낼_수_없다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
        ConfirmTokenStore store = new ConfirmTokenStore(clock);
        String token = store.issue(PENDING);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    void 모르는_토큰은_비어_있다() {
        assertThat(new ConfirmTokenStore(Clock.systemDefaultZone()).consume("nope")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
