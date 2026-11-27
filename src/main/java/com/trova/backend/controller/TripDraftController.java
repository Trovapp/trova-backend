package com.trova.backend.controller;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.service.AutoDraftQueryService;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.DraftAdmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import com.trova.backend.service.TripDraftApprovalService;
import com.trova.backend.service.TripPlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 일정 에이전트 초안 API(#106). 만들기는 비동기로 시작하고, 앱은 상태를 다시 조회한다.
 * 시작 전 질문에는 answer로 답하고(다시 비동기), 준비된 초안은 approve해야 여행이 된다.
 */
@RestController
public class TripDraftController {

    private final CurrentUserService currentUserService;
    private final TripPlannerService tripPlannerService;
    private final DraftAdmissionService admissionService;
    private final TripDraftRepository tripDraftRepository;
    private final TripDraftApprovalService tripDraftApprovalService;
    private final AutoDraftQueryService autoDraftQueryService;
    // 초안 만들기는 대기열로(#396) — 운영은 Kafka라 만드는 중에 서버가 재시작돼도 다시 받아 끝까지 한다.
    private final DraftJobQueue draftJobQueue;

    public TripDraftController(CurrentUserService currentUserService, TripPlannerService tripPlannerService,
                               TripDraftRepository tripDraftRepository, TripDraftApprovalService tripDraftApprovalService,
                               AutoDraftQueryService autoDraftQueryService,
                               DraftJobQueue draftJobQueue, DraftAdmissionService admissionService) {
        this.admissionService = admissionService;
        this.currentUserService = currentUserService;
        this.tripPlannerService = tripPlannerService;
        this.tripDraftRepository = tripDraftRepository;
        this.tripDraftApprovalService = tripDraftApprovalService;
        this.autoDraftQueryService = autoDraftQueryService;
        this.draftJobQueue = draftJobQueue;
    }

    /** choice: SPLIT(모든 영상, 지역별로 날 나누기) / ONLY(jobIds의 영상만). */
    public record AnswerRequest(String choice, List<Long> jobIds) {
    }

    public record ApproveRequest(String title) {
    }

    public record ApproveResponse(Long tripId) {
    }

    public record CreateDraftRequest(List<Long> jobIds, String message, Long replacesDraftId) {
    }

    public record CreateDraftResponse(Long draftId, String status) {
    }

    // summaryJson은 서버가 만든 JSON 문자열 그대로 넘긴다(1일차: 모은 장소·영업시간 확인 요약).
    public record DraftResponse(Long id, String status, String message, List<Long> jobIds, Integer days,
                                String startDate, String requestSource, String question, String summaryJson,
                                String draftJson, Integer geminiCalls, String errorMessage, String answer, Long tripId,
                                // 위치를 바로잡은 장소가 있거나 영상을 다시 분석했으면 true — 앱이 "다시 짜기"를 권한다(#414, #416).
                                boolean placesChanged,
                                // 영상마다 지금의 대표 분석 번호. jobIds와 다르면 그 영상을 다시 분석한 것이다 — 앱이 다시 짤 때
                                // 이 번호를 쓴다(#416).
                                List<Long> currentJobIds, boolean dismissed) {
        static DraftResponse from(TripDraft d, List<Long> currentJobIds) {
            boolean reanalyzed = d.getTripId() == null && !currentJobIds.equals(d.getJobIds());
            return new DraftResponse(d.getId(), d.getStatus().name(), d.getMessage(), d.getJobIds(), d.getDays(),
                    d.getStartDate() == null ? null : d.getStartDate().toString(), d.getRequestSource(),
                    d.getQuestion(), d.getSummaryJson(), d.getDraftJson(), d.getGeminiCalls(), d.getErrorMessage(),
                    d.getAnswer(), d.getTripId(), d.getPlacesChangedAt() != null || reanalyzed, currentJobIds,
                    d.getDismissedAt() != null);
        }
    }

    @Operation(summary = "일정 초안 접수", description = "최초 접수 후 24시간 미만이면 같은 사용자·원본·정규화 본문의 "
            + "미확정 초안을 재생한다. 키가 없으면 승인된 초안도 재생한다. 503 대기 키는 다음 동일 입력 접수와 연결되며 접수된 키는 상태와 관계없이 재생한다.")
    @ApiResponse(responseCode = "202", description = "최초 접수 또는 같은 본문·키에 의한 재생",
            content = @Content(examples = @ExampleObject(value = "{\"draftId\":42,\"status\":\"PENDING\"}")))
    @ApiResponse(responseCode = "409", description = "같은 원본을 다시 짜는 중. 최초 접수 후 24시간 미만이고 본문이 같으면 같은 키로 기존 202를 복구할 수 있다.",
            content = @Content(examples = @ExampleObject(value = """
                    {"code":"DRAFT_REPLACEMENT_IN_PROGRESS","message":"이 일정을 이미 다시 짜고 있어요.",
                     "status":409,"traceId":"example","activeDraftId":42}
                    """)))
    @ApiResponse(responseCode = "503", description = "구버전 작업의 원본을 확인할 수 없어 Retry-After 이후 재시도")
    @PostMapping("/api/trip-drafts")
    public ResponseEntity<?> create(Authentication authentication, @RequestBody CreateDraftRequest request,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String header) {
        Long userId = currentUserService.resolveUserId(authentication);
        UUID key = parseKey(header);
        if (header != null && key == null) {
            return ResponseEntity.badRequest().build();
        }
        DraftAdmissionService.Admission result = admissionService.admit(userId, request.jobIds(), request.message(),
                request.replacesDraftId(), key);
        return switch (result.outcome()) {
            case CREATED, REPLAY -> {
                if (result.outcome() == DraftAdmissionService.Outcome.CREATED) {
                    draftJobQueue.enqueue(result.draftId());
                }
                yield ResponseEntity.accepted().body(new CreateDraftResponse(result.draftId(), "PENDING"));
            }
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "DRAFT_REPLACEMENT_IN_PROGRESS", "activeDraftId", result.draftId()));
            case LEGACY_BUSY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "30").body(Map.of("code", "DRAFT_LEGACY_JOB_IN_PROGRESS"));
            case INVALID -> ResponseEntity.badRequest().build();
        };
    }

    @Operation(summary = "자동 재시도 전 같은 키 또는 입력의 초안 상태 조회")
    @PostMapping("/api/trip-drafts/replay")
    public ResponseEntity<DraftAdmissionService.Replay> replay(Authentication authentication,
            @RequestBody CreateDraftRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String header) {
        Long userId = currentUserService.resolveUserId(authentication);
        UUID key = parseKey(header);
        if (header != null && key == null) {
            return ResponseEntity.badRequest().build();
        }
        return admissionService.lookup(userId, request.jobIds(), request.message(), request.replacesDraftId(), key)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static UUID parseKey(String header) {
        if (header == null) {
            return null;
        }
        try {
            UUID key = UUID.fromString(header);
            return key.toString().equalsIgnoreCase(header) ? key : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
                draftJobQueue.enqueue(id);
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

    // 만드는 중이면 앱이 1초마다 부른다 — 사용자 ID만 메모리에서 확인해 사용자 행 조회를 줄인다(ADR 0036, #473).
    @GetMapping("/api/trip-drafts/{id}")
    public ResponseEntity<DraftResponse> get(Authentication authentication, @PathVariable Long id) {
        Long userId = currentUserService.resolveUserId(authentication);
        return tripDraftRepository.findById(id)
                .filter(d -> d.getUser().getId().equals(userId))
                .map(d -> ResponseEntity.ok(DraftResponse.from(d, tripPlannerService.currentJobIds(d))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 홈 화면 카드용 자동 생성 초안 목록(#136).
    // placeCount: 초안에 실제로 들어간 장소 수(아직 짜는 중이면 null) — 카드가 영상의 장소 수를 보여 줘서
    // 7곳 중 1곳을 뺀 초안인데 "장소 7곳"으로 보였다(5차 수정 확인 QA, #426).
    public record AutoDraftResponse(Long draftId, String status, Long jobId, String videoTitle, Integer days,
                                    String createdAt, Integer placeCount) {
    }

    @GetMapping("/api/trip-drafts/auto")
    public List<AutoDraftResponse> autoDrafts(Authentication authentication) {
        return autoDraftQueryService.list(currentUserService.resolveUserId(authentication));
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
