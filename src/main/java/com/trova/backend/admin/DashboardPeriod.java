package com.trova.backend.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** 대시보드 기간 — 오늘을 포함한 최근 N일(7·14·30). 날짜는 서버 기본 시간대 기준. */
public record DashboardPeriod(int days, LocalDate from, LocalDate to) {

    private static final Set<Integer> ALLOWED = Set.of(7, 14, 30);

    public static DashboardPeriod of(int days) {
        if (!ALLOWED.contains(days)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days는 7, 14, 30 중 하나여야 합니다");
        }
        LocalDate to = LocalDate.now();
        return new DashboardPeriod(days, to.minusDays(days - 1L), to);
    }

    public LocalDateTime since() {
        return from.atStartOfDay();
    }
}
