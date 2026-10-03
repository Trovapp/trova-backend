package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 영상 처리·일정 재구성은 서버 메모리의 @Async 대기열에서 돌아서, 서버가 재시작되면 진행 중이거나 대기 중이던
 * 작업이 사라지고 DB에는 PENDING/PROCESSING으로 영원히 남는다(#43). 그러면 같은 링크 재제출이 멈춘 작업을
 * 돌려주고, 삭제는 409로 막히고, 앱은 계속 폴링한다. 오래 갱신되지 않은 작업을 FAILED로 바꿔 풀어준다.
 *
 * "서버 시작 시 진행 중인 작업 전부"가 아니라 "오래 갱신되지 않은 작업"만 고르는 이유: 로컬·배포 서버가 같은
 * DB를 쓰므로, 한 서버가 뜰 때 다른 서버가 지금 처리 중인 작업까지 실패로 만들면 안 된다.
 */
@Service
public class OrphanedJobRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OrphanedJobRecoveryService.class);

    // 실측 처리 시간은 영상 처리 중앙값 26초·재구성 최대 38초. 대기열이 가득 찼을 때 마지막 작업이 시작되기까지
    // (50개 × 26초 ÷ 동시 2개 ≈ 11분) 갱신 없이 기다릴 수 있어 그보다 길게 잡는다.
    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    // 사용자에게 그대로 보여줘도 되는 문구라 재구성 상태 응답이 원문 대신 이 값은 그대로 내려준다(#95).
    public static final String STALE_MESSAGE = "처리가 중단됐어요. 다시 시도해주세요.";
    private static final List<JobStatus> IN_FLIGHT = List.of(JobStatus.PENDING, JobStatus.PROCESSING);
    // 일정 초안(#106)도 같은 @Async 대기열 문제를 겪는다. NEEDS_INPUT은 사용자 답을 기다리는 정상 상태라 고르지 않는다.
    private static final List<TripDraftStatus> DRAFT_IN_FLIGHT = List.of(TripDraftStatus.PENDING, TripDraftStatus.PROCESSING);

    private final ProcessingJobRepository processingJobRepository;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final TripDraftRepository tripDraftRepository;

    public OrphanedJobRecoveryService(
            ProcessingJobRepository processingJobRepository, TripReplanJobRepository tripReplanJobRepository,
            TripDraftRepository tripDraftRepository
    ) {
        this.processingJobRepository = processingJobRepository;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.tripDraftRepository = tripDraftRepository;
    }

    /** 서버가 뜰 때 한 번 — 직전 종료로 멈춘 작업을 바로 정리한다. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverOnStartup() {
        failStaleJobsInternal();
    }

    /** 재시작이 아니어도 외부 호출이 멈춰 걸린 작업이 있을 수 있어 주기적으로도 정리한다. */
    @Scheduled(fixedRate = 5, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void failStaleJobs() {
        failStaleJobsInternal();
    }

    private void failStaleJobsInternal() {
        // updatedAt은 엔티티가 서버 기본 시간대의 LocalDateTime.now()로 기록하므로 같은 기준으로 비교한다.
        LocalDateTime threshold = LocalDateTime.now().minus(STALE_AFTER);
        List<ProcessingJob> processingJobs = processingJobRepository.findByStatusInAndUpdatedAtBefore(IN_FLIGHT, threshold);
        processingJobs.forEach(job -> job.markFailed(STALE_MESSAGE));
        List<TripReplanJob> replanJobs = tripReplanJobRepository.findByStatusInAndUpdatedAtBefore(IN_FLIGHT, threshold);
        replanJobs.forEach(job -> job.markFailed(STALE_MESSAGE));
        List<TripDraft> drafts = tripDraftRepository.findByStatusInAndUpdatedAtBefore(DRAFT_IN_FLIGHT, threshold);
        drafts.forEach(draft -> draft.markFailed(STALE_MESSAGE));
        if (!processingJobs.isEmpty() || !replanJobs.isEmpty() || !drafts.isEmpty()) {
            log.warn("멈춘 작업 정리: 영상 처리 {}건, 일정 재구성 {}건, 일정 초안 {}건을 FAILED로 변경(기준 {}분 무갱신)",
                    processingJobs.size(), replanJobs.size(), drafts.size(), STALE_AFTER.toMinutes());
        }
    }
}
