package com.trova.backend.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** DB가 돌려준 "건수가 있는 날"만의 목록을 기간 전체로 펼치고 빈 날은 0으로 채운다. */
public final class DailySeries {

    private DailySeries() {
    }

    public static List<DailyCount> fill(DashboardPeriod period, List<DailyCount> rows) {
        Map<LocalDate, Long> byDate = rows.stream()
                .collect(Collectors.toMap(DailyCount::date, DailyCount::count, Long::sum));
        List<DailyCount> filled = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            filled.add(new DailyCount(d, byDate.getOrDefault(d, 0L)));
        }
        return filled;
    }
}
