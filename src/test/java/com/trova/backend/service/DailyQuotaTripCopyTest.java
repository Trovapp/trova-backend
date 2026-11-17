package com.trova.backend.service;

import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyQuotaTripCopyTest {

    @Test
    void 복사_한도는_장소_호출과_사용자별로_분리하고_한국_자정에_초기화한다() {
        Clock clock = mock(Clock.class);
        ZoneId korea = ZoneId.of("Asia/Seoul");
        when(clock.withZone(korea)).thenReturn(Clock.fixed(Instant.parse("2026-11-17T14:59:59Z"), korea));
        DailyQuotaService quota = new DailyQuotaService(mock(ProcessingJobRepository.class), 10, 1, 2, clock);
        User user = mock(User.class);
        User other = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(other.getId()).thenReturn(2L);

        quota.consumePlaceCall(user);
        quota.consumeTripCopy(user);
        quota.consumeTripCopy(user);

        assertThatThrownBy(() -> quota.consumeTripCopy(user))
                .isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        assertThatCode(() -> quota.consumeTripCopy(other)).doesNotThrowAnyException();
        assertThatThrownBy(() -> quota.consumePlaceCall(user))
                .isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        when(clock.withZone(korea)).thenReturn(Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), korea));
        assertThatCode(() -> quota.consumeTripCopy(user)).doesNotThrowAnyException();
    }
}
