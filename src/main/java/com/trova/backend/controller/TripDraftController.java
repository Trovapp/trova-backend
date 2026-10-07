package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripDraftApprovalService;
import com.trova.backend.service.TripPlannerService;
import com.trova.backend.service.TripService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 일정 에이전트 초안 API(#106). 만들기는 비동기로 시작하고, 앱은 상태를 다시 조회한다.
 * 시작 전 질문에는 answer로 답하고(다시 비동기), 준비된 초안은 approve해야 여행이 된다.
 */
@RestController
public class TripDraftController {

    private final CurrentUserService currentUserService;
    private final TripPlannerService tripPlannerService;
    private final TripDraftRepository tripDraftRepository;
    private final TripDraftApprovalService tripDraftApprovalService;
    private final ProcessingJobRepository processingJobRepository;
    private final TripService tripService;

    public TripDraftController(CurrentUserService currentUserService, TripPlannerService tripPlannerService,
                               TripDraftRepository tripDraftRepository, TripDraftApprovalService tripDraftApprovalService,
                               ProcessingJobRepository processingJobRepository, TripService tripService) {
        this.currentUserService = currentUserService;
        this.tripPlannerService = tripPlannerService;
        this.tripDraftRepository = tripDraftRepository;
        this.tripDraftApprovalService = tripDraftApprovalService;
        this.processingJobRepository = processingJobRepository;
        this.tripService = tripService;
    }

    /** choice: SPLIT(모든 영상, 지역별로 날 나누기) / ONLY(jobIds의 영상만). */
    public record AnswerRequest(String choice, List<Long> jobIds) {
    }

    public record ApproveRequest(String title) {
    }

    public record ApproveResponse(Long tripId) {
    }

    public record CreateDraftRequest(List<Long> jobIds, String message) {
    }

    public record CreateDraftResponse(Long draftId, String status) {
    }

    // summaryJson은 서버가 만든 JSON 문자열 그대로 넘긴다(1일차: 모은 장소·영업시간 확인 요약).
    public record DraftResponse(Long id, String status, String message, List<Long> jobIds, Integer days,
                                String startDate, String requestSource, String question, String summaryJson,
                                String draftJson, Integer geminiCalls, String errorMessage, String answer, Long tripId) {
        static DraftResponse from(TripDraft d) {
            return new DraftResponse(d.getId(), d.getStatus().name(), d.getMessage(), d.getJobIds(), d.getDays(),
                    d.getStartDate() == null ? null : d.getStartDate().toString(), d.getRequestSource(),
                    d.getQuestion(), d.getSummaryJson(), d.getDraftJson(), d.getGeminiCalls(), d.getErrorMessage(),
                    d.getAnswer(), d.getTripId());
        }
    }

    @PostMapping("/api/trip-drafts")
    public ResponseEntity<?> create(Authentication authentication, @RequestBody CreateDraftRequest request) {
        User user = currentUserService.resolve(authentication);
        return tripPlannerService.create(user, request == null ? null : request.jobIds(),
                        request == null ? null : request.message())
                .<ResponseEntity<?>>map(draft -> {
                    tripPlannerService.process(draft.getId());
                    return ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(new CreateDraftResponse(draft.getId(), draft.getStatus().name()));
                })
                .orElseGet(() -> ResponseEntity.badRequest().build());
    }

    @PostMapping("/api/trip-drafts/{id}/answer")
    public ResponseEntity<Void> answer(Authentication authentication, @PathVariable Long id,
                                       @RequestBody(required = false) AnswerRequest request) {
        User user = currentUserService.resolve(authentication);
        TripPlannerService.AnswerOutcome outcome = tripPlannerService.answer(user, id,
                request == null ? null : request.choice(), request == null ? null : request.jobIds());
        return switch (outcome) {
            case OK -> {
                // answer의 트랜잭션이 커밋된 뒤에 시작해야 처리 스레드가 바뀐 영상 목록을 읽는다.
                tripPlannerService.process(id);
                yield ResponseEntity.accepted().build();
            }
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_WAITING -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case BAD_REQUEST -> ResponseEntity.badRequest().build();
        };
    }

    @PostMapping("/api/trip-drafts/{id}/approve")
    public ResponseEntity<ApproveResponse> approve(Authentication authentication, @PathVariable Long id,
                                                   @RequestBody(required = false) ApproveRequest request) {
        User user = currentUserService.resolve(authentication);
        TripDraftApprovalService.Approval approval = tripDraftApprovalService.approve(user, id,
                request == null ? null : request.title());
        return switch (approval.outcome()) {
            case OK -> ResponseEntity.ok(new ApproveResponse(approval.tripId()));
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_READY -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    @GetMapping("/api/trip-drafts/{id}")
    public ResponseEntity<DraftResponse> get(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return tripDraftRepository.findById(id)
                .filter(d -> d.getUser().getId().equals(user.getId()))
                .map(d -> ResponseEntity.ok(DraftResponse.from(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 홈 화면 카드용 자동 생성 초안 목록(#136).
    public record AutoDraftResponse(Long draftId, String status, Long jobId, String videoTitle, Integer days, String createdAt) {
    }

    private static final List<TripDraftStatus> AUTO_VISIBLE =
            List.of(TripDraftStatus.PENDING, TripDraftStatus.PROCESSING, TripDraftStatus.READY);

    private record DraftWithJob(TripDraft draft, ProcessingJob job) {
    }

    @GetMapping("/api/trip-drafts/auto")
    public List<AutoDraftResponse> autoDrafts(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return tripDraftRepository
                .findByUserAndAutoCreatedTrueAndDismissedAtIsNullAndStatusInOrderByCreatedAtDesc(user, AUTO_VISIBLE)
                .stream()
                .map(d -> new DraftWithJob(d, processingJobRepository.findById(d.getJobIds().get(0)).orElse(null)))
                // 같은 영상으로 이미 여행이 만들어졌으면(다른 초안을 승인하는 등) 카드가 중복으로 남는다 — 빼고 보여준다.
                .filter(dj -> dj.job() == null || tripService.findExistingTripForVideo(user, dj.job()).isEmpty())
                .limit(10)
                .map(dj -> new AutoDraftResponse(dj.draft().getId(), dj.draft().getStatus().name(), dj.draft().getJobIds().get(0),
                        dj.job() == null ? null : dj.job().getTitle(), dj.draft().getDays(), dj.draft().getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/api/trip-drafts/{id}/dismiss")
    @Transactional
    public ResponseEntity<Void> dismiss(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        TripDraft draft = tripDraftRepository.findById(id).filter(d -> d.getUser().getId().equals(user.getId())).orElse(null);
        if (draft == null) return ResponseEntity.notFound().build();
        if (draft.getStatus() == TripDraftStatus.APPROVED) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        draft.dismiss();
        tripDraftRepository.save(draft);
        return ResponseEntity.noContent().build();
    }
}
