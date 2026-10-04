package com.trova.backend.controller;

import com.trova.backend.service.DailyQuotaService;
import org.springframework.core.task.TaskRejectedException;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlanService;
import com.trova.backend.entity.MeteredFeature;
import com.trova.backend.service.PlaceExtractionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
public class SharesController {

    private final CurrentUserService currentUserService;
    private final PlanService planService;
    private final DailyQuotaService dailyQuotaService;
    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;
    private final SavedPlaceRepository savedPlaceRepository;

    public SharesController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            PlaceExtractionService placeExtractionService,
            DailyQuotaService dailyQuotaService,
            SavedPlaceRepository savedPlaceRepository,
            PlanService planService
    ) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.currentUserService = currentUserService;
        this.dailyQuotaService = dailyQuotaService;
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
        this.planService = planService;
    }

    // reanalyze: 이미 분석한 영상이라도 새로 분석한다(앱의 "다시 분석하기"). 없으면 false.
    public record CreateShareRequest(String url, Boolean reanalyze) {
    }

    // alreadyAnalyzed: 새로 분석하지 않고 이미 있는 결과(jobId)를 알려준 경우 true(#87). 예전 앱은 이 칸을 몰라도
    // status=DONE인 작업으로 받아 결과 화면으로 넘어간다.
    public record ShareResponse(Long jobId, String status, boolean alreadyAnalyzed) {
        ShareResponse(Long jobId, String status) {
            this(jobId, status, false);
        }
    }

    public record ErrorResponse(String message) {
    }

    // 같은 사용자의 같은 영상 제출을 한 번에 하나씩 처리하는 잠금(#97). "이미 있는 결과/처리 중인 작업" 확인과 새 작업 저장
    // 사이에 틈이 있어, 동시에 들어온 요청 5개가 모두 확인을 통과해 작업 5개(Gemini 호출 16번)가 만들어졌다(로컬 실측).
    // 영상마다 잠금을 만들면 계속 쌓이므로 개수를 고정한 묶음에서 해시로 골라 쓴다 — 다른 영상이 같은 잠금에 걸리면
    // 아주 잠깐 순서대로 처리될 뿐 결과는 같다. 서버가 한 대(운영 E2.1.Micro)라 프로세스 안 잠금으로 충분하다.
    private static final int LOCK_STRIPES = 64;
    private static final Object[] SUBMISSION_LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            SUBMISSION_LOCKS[i] = new Object();
        }
    }

    private static Object submissionLock(User user, String canonicalUrl) {
        return SUBMISSION_LOCKS[Math.floorMod((user.getId() + "|" + canonicalUrl).hashCode(), LOCK_STRIPES)];
    }

    @PostMapping("/api/shares")
    public ResponseEntity<?> create(
            Authentication authentication,
            @RequestBody CreateShareRequest request
    ) {
        // 사용자가 보낸 링크 대신 영상 ID로 다시 만든 정식 주소만 저장하고 yt-dlp에 넘긴다(#49).
        Optional<ShareUrl> shareUrl = ShareUrl.parse(request == null ? null : request.url());
        if (shareUrl.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("유튜브 영상·쇼츠 또는 인스타그램 릴스·게시물 링크만 등록할 수 있어요."));
        }

        String url = shareUrl.get().canonicalUrl();
        SourcePlatform platform = shareUrl.get().platform();
        User user = currentUserService.resolve(authentication);

        // 확인부터 작업 저장(커밋)까지를 잠금 안에서 한다 — 저장이 끝나야 다음 요청의 "처리 중" 확인에 보인다.
        synchronized (submissionLock(user, url)) {
            return createOrReuse(user, url, platform, Boolean.TRUE.equals(request.reanalyze()));
        }
    }

    private ResponseEntity<?> createOrReuse(User user, String url, SourcePlatform platform, boolean reanalyze) {
        // 이미 분석해 장소가 남아 있는 영상이면 새로 분석하지 않고 그 결과를 알려준다(#87). 운영에서 같은 영상을
        // 다시 넣을 때마다 새로 분석해 결과가 쌓였다(김해 5번, 사당 3번) — Gemini 호출과 무료 한도도 그만큼 썼다.
        // 결과의 장소를 모두 지운 영상은 대표 결과가 없으므로 새로 분석한다.
        if (!reanalyze) {
            Long existingJobId = VideoResults.latestJobIdByVideo(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user))
                    .get(url);
            if (existingJobId != null) {
                return ResponseEntity.ok(new ShareResponse(existingJobId, JobStatus.DONE.name(), true));
            }
        }

        // 같은 URL이 이미 처리 대기/진행 중이면 새 job을 또 만들지 않는다 — 중복 제출로
        // Gemini/카카오 호출이 두 번 나가는 걸 막기 위함. 동시에 도착한 요청은 위 잠금이 순서대로 세운다(#97).
        List<ProcessingJob> inFlight = processingJobRepository.findByUserAndSourceUrlAndStatusIn(
                user, url, List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (!inFlight.isEmpty()) {
            ProcessingJob existing = inFlight.get(0);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ShareResponse(existing.getId(), existing.getStatus().name()));
        }

        planService.check(user, MeteredFeature.ANALYSIS);
        dailyQuotaService.checkShare(user);
        ProcessingJob job = processingJobRepository.save(new ProcessingJob(user, url, platform));
        try {
            placeExtractionService.process(job.getId());
        } catch (TaskRejectedException e) {
            // 대기열이 가득 차 실행기가 거절하면, 방금 만든 작업을 지운다 — 남기면 아무도 처리하지 않는
            // PENDING 작업이 돼 앱의 "처리 중" 표시가 영원히 사라지지 않는다(#31). 응답은 503(TaskRejectionHandler).
            processingJobRepository.delete(job);
            throw e;
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ShareResponse(job.getId(), job.getStatus().name()));
    }
}
