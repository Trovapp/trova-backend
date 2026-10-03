package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** 3일차 검증·수정·그래프 테스트가 함께 쓰는 장소·초안 만들기. 2026-10-05는 월요일이다. */
final class PlanFixtures {

    // 월요일 휴무, 화~일 10:00~18:00 (Google: 0=일요일)
    static final String CLOSED_MONDAY = periods(new int[]{0, 2, 3, 4, 5, 6}, 10, 18);
    // 매일 10:00~18:00
    static final String EVERY_DAY = periods(new int[]{0, 1, 2, 3, 4, 5, 6}, 10, 18);

    private PlanFixtures() {
    }

    static String periods(int[] days, int open, int close) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < days.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"open\":{\"day\":").append(days[i]).append(",\"hour\":").append(open).append(",\"minute\":0},")
                    .append("\"close\":{\"day\":").append(days[i]).append(",\"hour\":").append(close).append(",\"minute\":0}}");
        }
        return sb.append(']').toString();
    }

    static SavedPlace place(long id, String name, String category, double lat, double lng, String periods) {
        SavedPlace p = mock(SavedPlace.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getPlaceName()).thenReturn(name);
        lenient().when(p.getCategory()).thenReturn(category);
        lenient().when(p.getLatitude()).thenReturn(lat);
        lenient().when(p.getLongitude()).thenReturn(lng);
        lenient().when(p.getOpeningPeriods()).thenReturn(periods);
        return p;
    }

    static DraftGenerator.Item item(SavedPlace p, String start, String end) {
        return new DraftGenerator.Item(p.getId(), p.getPlaceName(), p.getCategory(), LocalTime.parse(start), LocalTime.parse(end),
                p.getLatitude(), p.getLongitude());
    }

    static DraftGenerator.Draft draft(List<List<DraftGenerator.Item>> days) {
        List<DraftGenerator.Day> list = new java.util.ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            list.add(new DraftGenerator.Day(i + 1, null, days.get(i)));
        }
        return new DraftGenerator.Draft(list, List.of(), List.of(), List.of());
    }

    static Map<Long, SavedPlace> byId(SavedPlace... places) {
        Map<Long, SavedPlace> map = new HashMap<>();
        for (SavedPlace p : places) {
            map.put(p.getId(), p);
        }
        return map;
    }
}
