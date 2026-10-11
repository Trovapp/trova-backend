package com.trova.backend.admin;

import com.trova.backend.entity.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 대시보드 맨 위 "지금 확인할 것" 카드 4개(#161). 기준값은 설정, 처음 값은 감으로 정함(실측 아님). */
@Service
public class DashboardSummaryService {

    public record Card(String key, Long value, long threshold, boolean alert, String note) {
    }

    public record DashboardSummary(List<Card> cards) {
    }

    // Gemini 무료 한도는 태평양 시간 자정에 초기화된다(qaflow 사용량 확인과 같은 기준).
    private static final ZoneId GEMINI_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    private final AiDashboardService aiDashboardService;
    private final PipelineDashboardService pipelineDashboardService;
    private final ServerDashboardService serverDashboardService;
    private final EntityManager entityManager;
    private final long geminiAlert;
    private final long geminiDailyLimit;
    private final long failuresAlert;
    private final long p90AlertMs;

    public DashboardSummaryService(AiDashboardService aiDashboardService, PipelineDashboardService pipelineDashboardService,
                                   ServerDashboardService serverDashboardService, EntityManager entityManager,
                                   @Value("${app.admin.dashboard.gemini-alert:800}") long geminiAlert,
                                   @Value("${app.admin.dashboard.gemini-daily-limit:1000}") long geminiDailyLimit,
                                   @Value("${app.admin.dashboard.failures-alert:5}") long failuresAlert,
                                   @Value("${app.admin.dashboard.p90-alert-ms:1000}") long p90AlertMs) {
        this.aiDashboardService = aiDashboardService;
        this.pipelineDashboardService = pipelineDashboardService;
        this.serverDashboardService = serverDashboardService;
        this.entityManager = entityManager;
        this.geminiAlert = geminiAlert;
        this.geminiDailyLimit = geminiDailyLimit;
        this.failuresAlert = failuresAlert;
        this.p90AlertMs = p90AlertMs;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime geminiDayStart = LocalDate.now(GEMINI_RESET_ZONE).atStartOfDay(GEMINI_RESET_ZONE)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        long gemini = aiDashboardService.geminiCallsSince(geminiDayStart);
        long failures = entityManager.createQuery(
                        // 분석 실패만 — 분석 뒤 일정 생성 실패는 status만 바꾸므로 분석 결과(analysisStatus)가 있으면 그것으로 센다(#161).
                        "select count(j) from ProcessingJob j where coalesce(j.analysisStatus, j.status) = :failed "
                                + "and coalesce(j.analysisFinishedAt, j.updatedAt) >= :since", Long.class)
                .setParameter("failed", JobStatus.FAILED)
                .setParameter("since", now.minusHours(24))
                .getSingleResult();
        long stuck = pipelineDashboardService.stuckJobs().size();
        Long p90 = serverDashboardService.maxP90Since(now.minusHours(1));

        return new DashboardSummary(List.of(
                card("geminiToday", gemini, geminiAlert, "이 DB에 기록된 호출만(같은 키를 쓰는 다른 DB의 서버 호출은 빠짐) · 하루 한도 " + geminiDailyLimit + "회(태평양 자정 기준)"),
                card("failures24h", failures, failuresAlert, "최근 24시간 분석 실패"),
                card("stuckJobs", stuck, 1, "진행 중인데 오래 갱신이 없는 작업"),
                card("p90LastHour", p90, p90AlertMs, "최근 1시간 응답 시간 p90(ms), 스냅샷 기준")));
    }

    private Card card(String key, Long value, long threshold, String note) {
        return new Card(key, value, threshold, value != null && value >= threshold, note);
    }
}
