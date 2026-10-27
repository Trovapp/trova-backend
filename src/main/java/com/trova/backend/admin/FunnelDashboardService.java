package com.trova.backend.admin;

import com.trova.backend.admin.FunnelQueries.Activity;
import com.trova.backend.admin.FunnelQueries.CohortUser;
import com.trova.backend.admin.FunnelQueries.PerUser;
import com.trova.backend.admin.FunnelQueries.Usage;
import com.trova.backend.service.AdminAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 사용자 퍼널·기능 사용 지표(#237, 설계 2026-10-27-user-funnel-design.md). 운영 DB에 이미 쌓인 기록만 쓴다 —
 * 화면을 열었다가 그냥 닫은 것처럼 기록이 남지 않는 행동은 여기서 알 수 없다.
 */
@Service
public class FunnelDashboardService {

    /** 퍼널 단계 — 앞 단계를 거친 사람만 다음 단계로 센다(영상 없이 여행만 만든 사람은 "영상 공유"에서 빠진다). */
    private enum Step {
        SIGNUP("signup", null),
        SHARED("shared", Activity.SHARES),
        GOT_PLACES("gotPlaces", Activity.SAVED_PLACES),
        MADE_TRIP("madeTrip", Activity.TRIPS),
        REFINED("refined", Activity.REFINES);

        private final String key;
        private final Activity activity;

        Step(String key, Activity activity) {
            this.key = key;
            this.activity = activity;
        }
    }

    // 마지막 활동 계산에 쓰는 기록.
    private static final List<Activity> ACTIVITY_SOURCES = List.of(Activity.SHARES, Activity.BOOKMARKS, Activity.DRAFTS,
            Activity.TRIPS, Activity.REFINES, Activity.WEATHER_REPLANS);

    private static final int USER_LIMIT = 100;

    public record FunnelStep(String key, long users) {
    }

    public record FeatureUsage(String key, long users, long count, Map<String, Long> details) {
    }

    public record UserActivity(Long id, String nickname, LocalDate joinedAt, LocalDateTime lastActiveAt,
                               long shares, long trips, long refines, String reachedStep) {
    }

    public record FunnelDashboard(int days, List<FunnelStep> steps, Long medianMinutesToFirstShare,
                                  List<FeatureUsage> features, int totalUsers, List<UserActivity> users) {
    }

    private final FunnelQueries queries;
    private final AdminAccessService adminAccessService;

    public FunnelDashboardService(FunnelQueries queries, AdminAccessService adminAccessService) {
        this.queries = queries;
        this.adminAccessService = adminAccessService;
    }

    @Transactional(readOnly = true)
    public FunnelDashboard funnel(int days) {
        LocalDateTime since = since(days);
        Set<String> adminKeys = adminAccessService.adminKeys();
        List<CohortUser> cohort = queries.cohort(since, adminKeys);
        List<Long> ids = cohort.stream().map(CohortUser::id).toList();

        Map<Step, Map<Long, PerUser>> stepRecords = new LinkedHashMap<>();
        for (Step step : Step.values()) {
            if (step.activity != null) {
                stepRecords.put(step, queries.perUser(step.activity, ids));
            }
        }
        Map<Long, Step> reached = new LinkedHashMap<>();
        Set<Long> remaining = new HashSet<>(ids);
        List<FunnelStep> steps = new ArrayList<>();
        for (Step step : Step.values()) {
            if (step.activity != null) {
                remaining.retainAll(stepRecords.get(step).keySet());
            }
            remaining.forEach(id -> reached.put(id, step));
            steps.add(new FunnelStep(step.key, remaining.size()));
        }

        Map<Long, PerUser> shares = stepRecords.get(Step.SHARED);
        List<Long> minutesToFirstShare = cohort.stream()
                .filter(u -> shares.containsKey(u.id()))
                .map(u -> Math.max(0, Duration.between(u.createdAt(), shares.get(u.id()).first()).toMinutes()))
                .toList();

        return new FunnelDashboard(days, steps, Percentiles.of(minutesToFirstShare, 0.5),
                features(since, adminKeys), cohort.size(), users(cohort, stepRecords, reached));
    }

    private List<FeatureUsage> features(LocalDateTime since, Set<String> adminKeys) {
        Usage shares = queries.usage(Activity.SHARES, since, adminKeys);
        Usage drafts = queries.usage(Activity.DRAFTS, since, adminKeys);
        return List.of(
                feature("shares", shares, Map.of("done", queries.usage(Activity.SHARES_DONE, since, adminKeys).count())),
                feature("savedPlaces", queries.usage(Activity.SAVED_PLACES, since, adminKeys), Map.of()),
                feature("bookmarks", queries.usage(Activity.BOOKMARKS, since, adminKeys), Map.of()),
                feature("drafts", drafts, Map.of(
                        "approved", queries.usage(Activity.DRAFTS_APPROVED, since, adminKeys).count(),
                        "auto", queries.usage(Activity.DRAFTS_AUTO, since, adminKeys).count())),
                feature("trips", queries.usage(Activity.TRIPS, since, adminKeys), Map.of()),
                feature("alternativeReplaced", queries.usage(Activity.ALTERNATIVE_REPLACED, since, adminKeys), Map.of()),
                feature("gapInserted", queries.usage(Activity.GAP_INSERTED, since, adminKeys), Map.of()),
                feature("chatLiked", queries.usage(Activity.CHAT_LIKED, since, adminKeys), Map.of()),
                feature("weatherReplans", queries.usage(Activity.WEATHER_REPLANS, since, adminKeys), Map.of()));
    }

    private static FeatureUsage feature(String key, Usage usage, Map<String, Long> details) {
        return new FeatureUsage(key, usage.users(), usage.count(), details);
    }

    private List<UserActivity> users(List<CohortUser> cohort, Map<Step, Map<Long, PerUser>> stepRecords,
                                     Map<Long, Step> reached) {
        List<Long> ids = cohort.stream().map(CohortUser::id).toList();
        Map<Activity, Map<Long, PerUser>> records = new LinkedHashMap<>();
        records.put(Activity.SHARES, stepRecords.get(Step.SHARED));
        records.put(Activity.TRIPS, stepRecords.get(Step.MADE_TRIP));
        records.put(Activity.REFINES, stepRecords.get(Step.REFINED));
        for (Activity activity : ACTIVITY_SOURCES) {
            records.computeIfAbsent(activity, a -> queries.perUser(a, ids));
        }
        return cohort.stream()
                .map(u -> new UserActivity(u.id(), u.nickname(), u.createdAt().toLocalDate(),
                        ACTIVITY_SOURCES.stream()
                                .map(a -> records.get(a).get(u.id()))
                                .filter(Objects::nonNull)
                                .map(PerUser::last)
                                .max(Comparator.naturalOrder())
                                .orElse(null),
                        count(records.get(Activity.SHARES), u.id()),
                        count(records.get(Activity.TRIPS), u.id()),
                        count(records.get(Activity.REFINES), u.id()),
                        reached.get(u.id()).key))
                .sorted(Comparator.comparing(UserActivity::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserActivity::joinedAt, Comparator.reverseOrder()))
                .limit(USER_LIMIT)
                .toList();
    }

    private static long count(Map<Long, PerUser> records, Long userId) {
        PerUser record = records.get(userId);
        return record == null ? 0 : record.count();
    }

    // 다른 탭과 같은 7·14·30일에 "전체"(0)만 더한다.
    private static LocalDateTime since(int days) {
        if (days == 0) {
            return null;
        }
        try {
            return DashboardPeriod.of(days).since();
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days는 0(전체), 7, 14, 30 중 하나여야 합니다");
        }
    }
}
