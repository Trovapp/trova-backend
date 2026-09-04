package com.trova.backend.service;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.repository.ItineraryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * startDate가 설정된 Trip의 앞으로 며칠(OpenWeatherMap 무료 티어 예보 범위 = 5일)
 * 안에 있는 일차만 주기적으로 스캔해서 날씨를 체크한다.
 *
 * 주기(4시간)는 Plan B에서 그대로 가져온 값 — Trova 실사용 트래픽으로 재검증한 적
 * 없음(0-5 원칙). 나중에 실측 후 조정 필요.
 */
@Component
public class WeatherCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeatherCheckScheduler.class);

    // OpenWeatherMap 무료 티어 "5 day / 3 hour forecast"의 예보 범위에 맞춘 상한.
    private static final int FORECAST_HORIZON_DAYS = 5;

    private final ItineraryRepository itineraryRepository;
    private final WeatherRecoveryService weatherRecoveryService;

    public WeatherCheckScheduler(ItineraryRepository itineraryRepository, WeatherRecoveryService weatherRecoveryService) {
        this.itineraryRepository = itineraryRepository;
        this.weatherRecoveryService = weatherRecoveryService;
    }

    @Scheduled(fixedRate = 4, timeUnit = java.util.concurrent.TimeUnit.HOURS, initialDelay = 1)
    public void checkUpcomingItineraries() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(FORECAST_HORIZON_DAYS);
        List<Itinerary> upcoming = itineraryRepository.findByDateBetween(today, horizon);

        if (upcoming.isEmpty()) {
            return;
        }
        log.info("날씨 스케줄러: 예보 범위 안 일차 {}개 체크 시작", upcoming.size());
        int notified = 0;
        for (Itinerary itinerary : upcoming) {
            try {
                if (weatherRecoveryService.checkAndNotify(itinerary).isPresent()) {
                    notified++;
                }
            } catch (Exception e) {
                log.warn("날씨 체크 실패(itineraryId={}) — 다음 일차로 계속 진행", itinerary.getId(), e);
            }
        }
        log.info("날씨 스케줄러: 완료, 알림 {}건 생성", notified);
    }
}
