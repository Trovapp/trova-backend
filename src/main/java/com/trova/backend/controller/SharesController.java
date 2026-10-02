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
    private final DailyQuotaService dailyQuotaService;
    private final ProcessingJobRepository processingJobRepository;
    private final PlaceExtractionService placeExtractionService;
    private final SavedPlaceRepository savedPlaceRepository;

    public SharesController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            PlaceExtractionService placeExtractionService,
            DailyQuotaService dailyQuotaService,
            SavedPlaceRepository savedPlaceRepository
    ) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.currentUserService = currentUserService;
        this.dailyQuotaService = dailyQuotaService;
        this.processingJobRepository = processingJobRepository;
        this.placeExtractionService = placeExtractionService;
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

        // 이미 분석해 장소가 남아 있는 영상이면 새로 분석하지 않고 그 결과를 알려준다(#87). 운영에서 같은 영상을
        // 다시 넣을 때마다 새로 분석해 결과가 쌓였다(김해 5번, 사당 3번) — Gemini 호출과 무료 한도도 그만큼 썼다.
        // 결과의 장소를 모두 지운 영상은 대표 결과가 없으므로 새로 분석한다.
        if (!Boolean.TRUE.equals(request.reanalyze())) {
            Long existingJobId = VideoResults.latestJobIdByVideo(savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user))
                    .get(url);
            if (existingJobId != null) {
                return ResponseEntity.ok(new ShareResponse(existingJobId, JobStatus.DONE.name(), true));
            }
        }

        // 같은 URL이 이미 처리 대기/진행 중이면 새 job을 또 만들지 않는다 — 중복 제출로
        // Gemini/카카오 호출이 두 번 나가는 걸 막기 위함. Trova는 단일 인스턴스라 분산 락
        // 없이 이 조회-후-생성만으로 충분하지만, 두 요청이 정말 동시에 도착하는 극히 드문
        // 경우까지 완벽히 막지는 못한다(체크와 저장 사이 짧은 틈은 남아있음).
        List<ProcessingJob> inFlight = processingJobRepository.findByUserAndSourceUrlAndStatusIn(
                user, url, List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (!inFlight.isEmpty()) {
            ProcessingJob existing = inFlight.get(0);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ShareResponse(existing.getId(), existing.getStatus().name()));
        }

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
