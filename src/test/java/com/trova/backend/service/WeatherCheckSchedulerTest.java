package com.trova.backend.service;

import com.trova.backend.repository.ItineraryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherCheckSchedulerTest {

    @Mock private ItineraryRepository itineraryRepository;
    @Mock private WeatherRecoveryService weatherRecoveryService;

    @Test
    void 검사_범위의_오늘은_서버_시간대가_아니라_한국_날짜다() {
        // UTC 9/28 16:00 = 한국 시간 9/29 01:00. 서버가 UTC여도 "오늘"은 9/29여야 한다(#39).
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T16:00:00Z"), ZoneId.of("Asia/Seoul"));
        WeatherCheckScheduler scheduler = new WeatherCheckScheduler(itineraryRepository, weatherRecoveryService, clock);
        when(itineraryRepository.findByDateBetween(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 4)))
                .thenReturn(List.of());

        scheduler.checkUpcomingItineraries();

        verify(itineraryRepository).findByDateBetween(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 4));
    }

    @Test
    void 날씨_확인은_한국_시간_8_12_16_20시에만_돈다() throws Exception {
        Scheduled scheduled = WeatherCheckScheduler.class.getMethod("checkUpcomingItineraries")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 8,12,16,20 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
