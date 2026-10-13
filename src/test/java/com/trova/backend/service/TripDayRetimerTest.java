package com.trova.backend.service;

import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TripPlace;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripDayRetimerTest {

    private static TripPlace place(String name, double lat, double lng, String start, String end) {
        TripPlace place = new TripPlace(null, name, null, null, lat, lng, null, null, 1, PlaceSource.NORMAL, null);
        if (start != null) {
            place.applyDetails(LocalTime.parse(start), LocalTime.parse(end), null, null);
        }
        return place;
    }

    @Test
    void 순서가_바뀌면_그날_가장_이른_시작부터_머무는_시간을_지켜_다시_매긴다() {
        // 동선 최적화로 12:10 식당이 첫 번째가 되고 09:00 마을이 두 번째가 된 상황(2026-10-13 PWA QA)
        TripPlace restaurant = place("도림원", 37.6370, 126.9400, "12:10", "13:10");
        TripPlace village = place("은평한옥마을", 37.6380, 126.9410, "09:00", "10:30");

        TripDayRetimer.retime(List.of(restaurant, village));

        assertThat(restaurant.getVisitStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(restaurant.getVisitEndTime()).isEqualTo(LocalTime.of(10, 0));
        // 가까운 곳이라 최소 이동 10분
        assertThat(village.getVisitStartTime()).isEqualTo(LocalTime.of(10, 10));
        assertThat(village.getVisitEndTime()).isEqualTo(LocalTime.of(11, 40));
    }

    @Test
    void 먼_곳은_직선거리_시속_30km로_이동_시간을_넣는다() {
        // 약 11km → 22분 → 10분 단위 올림 30분
        TripPlace first = place("A", 37.5665, 126.9780, "09:00", "10:00");
        TripPlace second = place("B", 37.6665, 126.9780, "15:00", "16:00");

        TripDayRetimer.retime(List.of(first, second));

        assertThat(second.getVisitStartTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(second.getVisitEndTime()).isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void 시간이_없는_장소가_있으면_시간을_건드리지_않는다() {
        TripPlace timed = place("A", 37.5665, 126.9780, "12:00", "13:00");
        TripPlace untimed = place("B", 37.5765, 126.9780, null, null);

        TripDayRetimer.retime(List.of(timed, untimed));

        assertThat(timed.getVisitStartTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(untimed.getVisitStartTime()).isNull();
    }
}
