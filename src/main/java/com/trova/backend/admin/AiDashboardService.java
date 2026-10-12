package com.trova.backend.admin;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** AI·외부 API 호출 탭(api_call_logs). 이 DB에 기록된 호출만 — 같은 Gemini 키를 쓰더라도 다른 DB를 쓰는 서버(로컬)의 호출은 빠진다. */
@Service
public class AiDashboardService {

    public record DailyAiStats(LocalDate date, long calls, long failures, long tokens) {
    }

    public record OperationCount(String provider, String operation, long calls) {
    }

    public record AiDashboard(int days, List<DailyAiStats> daily, List<OperationCount> byOperation, Double callsPerJob) {
    }

    public record CallRow(LocalDateTime createdAt, String provider, String operation, boolean success,
                          Integer totalTokens, Long jobId) {
    }

    private final EntityManager entityManager;

    public AiDashboardService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public AiDashboard ai(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<CallRow> rows = entityManager.createQuery(
                        "select new com.trova.backend.admin.AiDashboardService$CallRow(c.createdAt, c.provider, c.operation, "
                                + "c.success, c.totalTokens, c.jobId) from ApiCallLog c where c.createdAt >= :since", CallRow.class)
                .setParameter("since", period.since())
                .getResultList();

        Map<LocalDate, List<CallRow>> byDate = rows.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate()));
        List<DailyAiStats> daily = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            List<CallRow> dayRows = byDate.getOrDefault(d, List.of());
            daily.add(new DailyAiStats(d, dayRows.size(),
                    dayRows.stream().filter(r -> !r.success()).count(),
                    dayRows.stream().map(CallRow::totalTokens).filter(Objects::nonNull).mapToLong(Integer::longValue).sum()));
        }

        List<OperationCount> byOperation = rows.stream()
                .collect(Collectors.groupingBy(r -> r.provider() + "|" + r.operation(), Collectors.counting()))
                .entrySet().stream()
                .map(e -> {
                    String[] key = e.getKey().split("\\|", 2);
                    return new OperationCount(key[0], key[1], e.getValue());
                })
                .sorted(Comparator.comparingLong(OperationCount::calls).reversed())
                .toList();

        // 영상 분석 작업(processing_jobs)에 붙은 호출만 — 추천 태깅·리뷰 요약은 job_id 자리에 System.nanoTime()을 넣어
        // 기록하므로 jobId가 있다고 모두 작업은 아니다(리뷰 지적, #161).
        Object[] jobCalls = entityManager.createQuery(
                        "select count(c), count(distinct c.jobId) from ApiCallLog c where c.createdAt >= :since "
                                + "and c.jobId in (select j.id from ProcessingJob j)", Object[].class)
                .setParameter("since", period.since())
                .getSingleResult();
        long jobs = (Long) jobCalls[1];
        Double callsPerJob = jobs == 0 ? null : (double) (Long) jobCalls[0] / jobs;

        return new AiDashboard(days, daily, byOperation, callsPerJob);
    }

    @Transactional(readOnly = true)
    public long geminiCallsSince(LocalDateTime since) {
        return entityManager.createQuery(
                        "select count(c) from ApiCallLog c where c.provider = 'gemini' and c.createdAt >= :since", Long.class)
                .setParameter("since", since)
                .getSingleResult();
    }
}
