package com.trova.backend.admin.action;

import com.trova.backend.admin.AiDashboardService;
import com.trova.backend.admin.FailureReason;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.PlaceExtractionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 일시 오류로 실패한 분석을 같은 링크로 새 작업을 만들어 다시 돌린다(기존 작업은 그대로 둔다).
 * 오늘 Gemini 사용량이 대시보드 경고 기준(app.admin.dashboard.gemini-alert) 이상이면 하지 않는다.
 */
@Component
public class RetryJobAction implements AdminActionHandler {

    private static final Set<FailureReason> RETRYABLE =
            EnumSet.of(FailureReason.AI_QUOTA, FailureReason.SOURCE_RATE_LIMITED, FailureReason.STALE);

    private final ProcessingJobRepository jobRepository;
    private final PlaceExtractionService placeExtractionService;
    private final AiDashboardService aiDashboardService;
    private final SuspendedUsers suspendedUsers;
    private final long geminiAlert;

    public RetryJobAction(ProcessingJobRepository jobRepository, PlaceExtractionService placeExtractionService,
                          AiDashboardService aiDashboardService, SuspendedUsers suspendedUsers,
                          @Value("${app.admin.dashboard.gemini-alert:800}") long geminiAlert) {
        this.jobRepository = jobRepository;
        this.placeExtractionService = placeExtractionService;
        this.aiDashboardService = aiDashboardService;
        this.suspendedUsers = suspendedUsers;
        this.geminiAlert = geminiAlert;
    }

    @Override
    public AdminAction action() {
        return AdminAction.RETRY_JOB;
    }

    @Override
    public ActionPreview preview(Long targetId, Long adminUserId) {
        ProcessingJob job = retryableJob(targetId);
        long gemini = checkGeminiUsage();
        return new ActionPreview(action(), job.getId(), job.getUser().getId(),
                "영상 1·오늘 Gemini " + gemini + "회", null);
    }

    /**
     * 트랜잭션으로 감싸지 않는다 — 새 작업 저장이 커밋된 뒤에 비동기 처리가 작업을 읽어야 한다(SharesController와 같은 방식).
     */
    @Override
    public String execute(Long targetId, Long adminUserId) {
        ProcessingJob job = retryableJob(targetId);
        checkGeminiUsage();
        ProcessingJob newJob = jobRepository.save(
                new ProcessingJob(job.getUser(), job.getSourceUrl(), job.getSourcePlatform()));
        try {
            placeExtractionService.process(newJob.getId());
        } catch (TaskRejectedException e) {
            // 남기면 아무도 처리하지 않는 PENDING 작업이 된다(#31).
            jobRepository.delete(newJob);
            throw AdminActionException.conflict("서버가 바빠요. 잠시 뒤 다시 해 주세요.");
        }
        return "새 작업 #" + newJob.getId();
    }

    /**
     * 일시 오류로 실패했고, 같은 사용자·같은 링크로 그 뒤에 만든 작업이 없을 때만(상태 무관). 관리자 사용자 상세에서도 쓴다.
     * 더 최근 작업이 있으면(재실행이 끝났거나 사용자가 다시 공유함) 예전 실패를 다시 돌리면 이미 분석한 영상을 또 분석한다(#87).
     * 정지·처리 중 중복·Gemini 사용량은 미리보기·실행에서 따로 본다.
     */
    public boolean isRetryable(ProcessingJob job) {
        return isRetryableFailure(job) && isLatest(job);
    }

    private static boolean isRetryableFailure(ProcessingJob job) {
        return job.getStatus() == JobStatus.FAILED && RETRYABLE.contains(FailureReason.classify(job.getErrorMessage()));
    }

    private boolean isLatest(ProcessingJob job) {
        return !jobRepository.existsByUserAndSourceUrlAndIdGreaterThan(job.getUser(), job.getSourceUrl(), job.getId());
    }

    private ProcessingJob retryableJob(Long targetId) {
        ProcessingJob job = jobRepository.findById(targetId).orElseThrow(AdminActionException::notFound);
        if (!isRetryableFailure(job)) {
            throw AdminActionException.conflict("일시 오류로 실패한 작업만 다시 실행해요.");
        }
        if (suspendedUsers.isSuspended(job.getUser().getId())) {
            throw AdminActionException.conflict("정지된 계정의 작업은 다시 실행하지 않아요.");
        }
        List<ProcessingJob> inFlight = jobRepository.findByUserAndSourceUrlAndStatusIn(
                job.getUser(), job.getSourceUrl(), List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (!inFlight.isEmpty()) {
            throw AdminActionException.conflict("같은 링크를 이미 처리하고 있어요.");
        }
        if (!isLatest(job)) {
            throw AdminActionException.conflict("이 영상은 더 최근 작업이 있어 다시 실행하지 않아요.");
        }
        return job;
    }

    private long checkGeminiUsage() {
        long gemini = aiDashboardService.geminiCallsSince(AiDashboardService.geminiDayStart());
        if (gemini >= geminiAlert) {
            throw AdminActionException.conflict("오늘 Gemini 사용량이 " + gemini + "회라 다시 실행하지 않아요.");
        }
        return gemini;
    }
}
