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
 * 시간이 없는 장소가 하나라도 있으면 사용자가 정한 시간을 섣불리 바꾸지 않도록 그대로 둔다.
 */
final class TripDayRetimer {

    private TripDayRetimer() {
    }

    static void retime(List<TripPlace> ordered) {
        if (ordered.isEmpty() || ordered.stream().anyMatch(p -> p.getVisitStartTime() == null || p.getVisitEndTime() == null)) {
            return;
        }
        LocalTime cursor = ordered.stream().map(TripPlace::getVisitStartTime).min(LocalTime::compareTo).orElseThrow();
        for (int i = 0; i < ordered.size(); i++) {
            TripPlace place = ordered.get(i);
            if (i > 0) {
                TripPlace prev = ordered.get(i - 1);
                cursor = cursor.plusMinutes(TravelTime.minutes(
                        prev.getLatitude(), prev.getLongitude(), place.getLatitude(), place.getLongitude()));
            }
            long stay = Math.max(0, Duration.between(place.getVisitStartTime(), place.getVisitEndTime()).toMinutes());
            LocalTime end = cursor.plusMinutes(stay);
            if (end.isBefore(cursor)) { // 자정을 넘기면 더 밀지 않는다(DraftFixer.retime과 같은 처리)
                end = LocalTime.of(23, 59);
            }
            place.applyDetails(cursor, end, null, null);
            cursor = end;
        }
    }
}
