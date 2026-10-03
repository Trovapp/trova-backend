package com.trova.backend.controller;

import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripPlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 일정 에이전트 초안 API(#106). 만들기는 비동기로 시작하고, 앱은 상태를 다시 조회한다. */
@RestController
public class TripDraftController {

    private final CurrentUserService currentUserService;
    private final TripPlannerService tripPlannerService;
    private final TripDraftRepository tripDraftRepository;

    public TripDraftController(CurrentUserService currentUserService, TripPlannerService tripPlannerService,
                               TripDraftRepository tripDraftRepository) {
        this.currentUserService = currentUserService;
        this.tripPlannerService = tripPlannerService;
        this.tripDraftRepository = tripDraftRepository;
    }

    public record CreateDraftRequest(List<Long> jobIds, String message) {
    }

    public record CreateDraftResponse(Long draftId, String status) {
    }

    // summaryJson은 서버가 만든 JSON 문자열 그대로 넘긴다(1일차: 모은 장소·영업시간 확인 요약).
    public record DraftResponse(Long id, String status, String message, List<Long> jobIds, Integer days,
                                String startDate, String requestSource, String question, String summaryJson,
                                String errorMessage) {
        static DraftResponse from(TripDraft d) {
            return new DraftResponse(d.getId(), d.getStatus().name(), d.getMessage(), d.getJobIds(), d.getDays(),
                    d.getStartDate() == null ? null : d.getStartDate().toString(), d.getRequestSource(),
                    d.getQuestion(), d.getSummaryJson(), d.getErrorMessage());
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

    @GetMapping("/api/trip-drafts/{id}")
    public ResponseEntity<DraftResponse> get(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return tripDraftRepository.findById(id)
                .filter(d -> d.getUser().getId().equals(user.getId()))
                .map(d -> ResponseEntity.ok(DraftResponse.from(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
