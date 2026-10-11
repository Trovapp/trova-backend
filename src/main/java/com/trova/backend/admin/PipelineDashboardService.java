package com.trova.backend.admin;

import com.trova.backend.entity.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 분석 파이프라인 탭. 처리 시간 백분위는 DB마다 함수가 달라(H2·Postgres) 기간 안 작업의 필요한 열만 읽어 Java에서 계산한다
 * (30일 행이 10만을 넘으면 DB 집계로 바꾼다).
 */
@Service
public class PipelineDashboardService {

    public record DailyJobStats(LocalDate date, long total, long done, long failed) {
    }

    public record ReasonCount(String reason, long count) {
    }

    public record StuckJob(Long id, String status, String stage, long minutesSinceUpdate) {
    }

    public record PipelineDashboard(int days, List<DailyJobStats> daily, Long durationP50Seconds,
                                    Long durationP90Seconds, List<ReasonCount> failureReasons, List<StuckJob> stuckJobs) {
    }

    public record JobRow(LocalDateTime createdAt, LocalDateTime updatedAt, JobStatus status, String errorMessage,
                         JobStatus analysisStatus, LocalDateTime analysisFinishedAt) {

        /** 분석이 처음 끝난 결과. 분석 뒤 일정 생성이 status를 다시 바꾸므로 있으면 이 값을 쓰고, 열이 생기기 전 작업은 status. */
        JobStatus outcome() {
            return analysisStatus != null ? analysisStatus : status;
        }

        LocalDateTime finishedAt() {
            return analysisFinishedAt != null ? analysisFinishedAt : updatedAt;
        }
    }

    private final EntityManager entityManager;
    private final int stuckAfterMinutes;

    public PipelineDashboardService(EntityManager entityManager,
                                    @Value("${app.admin.dashboard.stuck-after-minutes:5}") int stuckAfterMinutes) {
        this.entityManager = entityManager;
        this.stuckAfterMinutes = stuckAfterMinutes;
    }

    @Transactional(readOnly = true)
    public PipelineDashboard pipeline(int days) {
        DashboardPeriod period = DashboardPeriod.of(days);
        List<JobRow> rows = entityManager.createQuery(
                        "select new com.trova.backend.admin.PipelineDashboardService$JobRow(j.createdAt, j.updatedAt, j.status, j.errorMessage, "
                                + "j.analysisStatus, j.analysisFinishedAt) "
                                + "from ProcessingJob j where j.createdAt >= :since", JobRow.class)
                .setParameter("since", period.since())
                .getResultList();

        Map<LocalDate, List<JobRow>> byDate = rows.stream().collect(Collectors.groupingBy(r -> r.createdAt().toLocalDate()));
        List<DailyJobStats> daily = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) {
            List<JobRow> dayRows = byDate.getOrDefault(d, List.of());
            daily.add(new DailyJobStats(d, dayRows.size(),
                    dayRows.stream().filter(r -> r.outcome() == JobStatus.DONE).count(),
                    dayRows.stream().filter(r -> r.outcome() == JobStatus.FAILED).count()));
        }

        List<Long> durations = rows.stream()
                .filter(r -> r.outcome() == JobStatus.DONE)
                .map(r -> Duration.between(r.createdAt(), r.finishedAt()).toSeconds())
                .toList();

        Map<FailureReason, Long> reasons = rows.stream()
                .filter(r -> r.outcome() == JobStatus.FAILED)
                .collect(Collectors.groupingBy(r -> FailureReason.classify(r.errorMessage()), Collectors.counting()));
        List<ReasonCount> failureReasons = Arrays.stream(FailureReason.values())
                .filter(reasons::containsKey)
                .map(reason -> new ReasonCount(reason.name(), reasons.get(reason)))
                .toList();

        return new PipelineDashboard(days, daily, Percentiles.of(durations, 0.5), Percentiles.of(durations, 0.9),
                failureReasons, stuckJobs());
    }

    /** 진행 중인데 stuck-after-minutes 넘게 갱신이 없는 작업(최대 20개, 오래된 순). 15분이 지나면 OrphanedJobRecoveryService가 FAILED로 바꾼다. */
    @Transactional(readOnly = true)
    public List<StuckJob> stuckJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusMinutes(stuckAfterMinutes);
        return entityManager.createQuery(
                        "select j.id, j.status, j.currentStage, j.updatedAt from ProcessingJob j "
                                + "where j.status in :inFlight and j.updatedAt < :threshold order by j.updatedAt", Object[].class)
                .setParameter("inFlight", List.of(JobStatus.PENDING, JobStatus.PROCESSING))
                .setParameter("threshold", threshold)
                .setMaxResults(20)
                .getResultList()
                .stream()
                .map(r -> new StuckJob((Long) r[0], String.valueOf(r[1]), r[2] == null ? null : String.valueOf(r[2]),
                        Duration.between((LocalDateTime) r[3], now).toMinutes()))
                .toList();
    }
}
