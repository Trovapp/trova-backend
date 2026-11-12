package com.trova.backend.service;

import com.trova.backend.entity.TripPlace;
import com.trova.backend.planner.TravelTime;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * 하루 일정의 순서가 바뀐 뒤 방문 시간을 새 순서대로 다시 매긴다. 동선 최적화·순서 바꾸기가 순서만 바꿔
 * 1번 12:10, 2번 09:00처럼 시간이 거꾸로 보였다(2026-10-13 PWA QA, #175).
 * 그날 가장 이른 시작 시각에서 출발해 장소마다 원래 머무는 시간을 지키고, 사이에 이동 시간(TravelTime)을 둔다.
 * 시간이 없는 장소는 기본 60분 머무는 것으로 보고 같이 매긴다 — 예전엔 하나라도 시간이 없으면 전체를 건너뛰어
 * 빈 시간에 끼워 넣은 장소(시간 없음) 하나 때문에 동선 다듬기 뒤 15:00 → 13:00 → 10:00처럼 거꾸로 남았다
 * (안드로이드 사용성 테스트 2026-11-12, #414). 모든 장소에 시간이 없으면 정할 기준이 없어 그대로 둔다.
 */
final class TripDayRetimer {

    private TripDayRetimer() {
    }

    static final int DEFAULT_STAY_MINUTES = 60;

    static void retime(List<TripPlace> ordered) {
        if (ordered.stream().noneMatch(TripDayRetimer::timed)) {
            return;
        }
        LocalTime cursor = ordered.stream().filter(TripDayRetimer::timed)
                .map(TripPlace::getVisitStartTime).min(LocalTime::compareTo).orElseThrow();
        for (int i = 0; i < ordered.size(); i++) {
            TripPlace place = ordered.get(i);
            if (i > 0) {
                TripPlace prev = ordered.get(i - 1);
                cursor = cursor.plusMinutes(TravelTime.minutes(
                        prev.getLatitude(), prev.getLongitude(), place.getLatitude(), place.getLongitude()));
            }
            long stay = timed(place)
                    ? Math.max(0, Duration.between(place.getVisitStartTime(), place.getVisitEndTime()).toMinutes())
                    : DEFAULT_STAY_MINUTES;
            LocalTime end = cursor.plusMinutes(stay);
            if (end.isBefore(cursor)) { // 자정을 넘기면 더 밀지 않는다(DraftFixer.retime과 같은 처리)
                end = LocalTime.of(23, 59);
            }
            place.applyDetails(cursor, end, null, null);
            cursor = end;
        }
    }

    private static boolean timed(TripPlace place) {
        return place.getVisitStartTime() != null && place.getVisitEndTime() != null;
    }

    /** 식당인지 — 검색·빈 시간·대안으로 들어온 장소는 구글 세부 타입(korean_restaurant 등)을 category로 쓴다(#414). */
    static boolean isRestaurant(String category) {
        return category != null && (category.equals("restaurant") || category.endsWith("_restaurant"));
    }
}
