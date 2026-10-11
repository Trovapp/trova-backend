package com.trova.backend.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class UsageDashboardService {

    public record UsageDashboard(int days, long totalUsers, List<DailyCount> newUsers, List<DailyCount> savedPlaces,
                                 List<DailyCount> trips, List<DailyCount> autoDrafts) {
    }

    private final DashboardQueries queries;

    public UsageDashboardService(DashboardQueries queries) {
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public UsageDashboard usage(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        LocalDateTime since = period.since();
        return new UsageDashboard(days, queries.countAll(DashboardQueries.CountedEntity.USERS),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.USERS, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.SAVED_PLACES, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.TRIPS, since)),
                DailySeries.fill(period, queries.dailyCounts(DashboardQueries.CountedEntity.AUTO_DRAFTS, since)));
    }
}
