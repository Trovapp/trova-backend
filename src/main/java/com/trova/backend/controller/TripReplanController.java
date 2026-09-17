package com.trova.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripReplanJobService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
public class TripReplanController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final TripReplanJobRepository tripReplanJobRepository;
    private final TripReplanJobService tripReplanJobService;

    public TripReplanController(
            CurrentUserService currentUserService,
            TripRepository tripRepository,
            TripReplanJobRepository tripReplanJobRepository,
            TripReplanJobService tripReplanJobService
    ) {
        this.currentUserService = currentUserService;
        this.tripRepository = tripRepository;
        this.tripReplanJobRepository = tripReplanJobRepository;
        this.tripReplanJobService = tripReplanJobService;
    }

    public record TripReplanRequest(Boolean indoorOnly) {
    }

    public record ReplanResultResponse(
            Long tripPlaceId, String originalName, TripController.AlternativeCandidateResponse candidate
    ) {
        static ReplanResultResponse from(TripReplanGraph.ReplanMatch match) {
            return new ReplanResultResponse(
                    match.tripPlaceId(), match.originalName(),
                    TripController.AlternativeCandidateResponse.from(match.candidate()));
        }
    }

    public record TripReplanResponse(List<ReplanResultResponse> replaced, List<Long> failedTripPlaceIds) {
        static TripReplanResponse from(TripReplanGraph.ReplanOutcome outcome) {
            return new TripReplanResponse(
                    outcome.matches().stream().map(ReplanResultResponse::from).toList(),
                    outcome.failedTripPlaceIds());
        }
    }

    public record CreateReplanJobResponse(Long jobId) {
    }

    public record ReplanJobStatusResponse(
            String status, int completedTargets, Integer totalTargets,
            TripReplanResponse result, String errorMessage
    ) {
    }

    @PostMapping("/api/trips/{tripId}/replan")
    public ResponseEntity<?> replan(
            Authentication authentication, @PathVariable Long tripId, @RequestBody TripReplanRequest request
    ) {
        User user = currentUserService.resolve(authentication);
        // v1은 "실내 위주로 바꾸기" 한 방향만 지원한다 — indoorOnly가 없거나
        // false면 재구성할 조건 자체가 없으므로 400.
        if (request.indoorOnly() == null || !request.indoorOnly()) {
            return ResponseEntity.badRequest().build();
        }
        return tripRepository.findById(tripId)
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .map(trip -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new CreateReplanJobResponse(resolveJobId(user, trip))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/trips/{tripId}/replan/{jobId}")
    public ResponseEntity<?> status(
            Authentication authentication, @PathVariable Long tripId, @PathVariable Long jobId
    ) {
        User user = currentUserService.resolve(authentication);
        return tripReplanJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()) && job.getTrip().getId().equals(tripId))
                .map(job -> ResponseEntity.ok(toStatusResponse(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static final long STALE_JOB_MINUTES = 5;

    // 같은 (user, trip, indoorOnly)로 PENDING/PROCESSING인 작업이 있으면 그 jobId를
    // 재사용한다 — 영상 파이프라인(SharesController)의 중복 제출 방지와 동일한 패턴.
    // updatedAt이 STALE_JOB_MINUTES 이내인 작업만 "살아있다"고 본다 — 배포/재시작으로
    // 스레드가 중간에 죽으면 PROCESSING 상태로 영원히 멈춘 row가 생기는데, 그걸
    // 무기한 재사용하면 해당 여행은 다시는 재구성을 못 돌리게 된다. 실측 응답시간이
    // 최악 75~80초였으므로 5분이면 정상 작업과 충분히 구분된다.
    private Long resolveJobId(User user, Trip trip) {
        List<TripReplanJob> inFlight = tripReplanJobRepository.findByUserAndTripAndIndoorOnlyAndStatusInAndUpdatedAtAfter(
                user, trip, true, List.of(JobStatus.PENDING, JobStatus.PROCESSING),
                LocalDateTime.now().minusMinutes(STALE_JOB_MINUTES));
        if (!inFlight.isEmpty()) {
            return inFlight.get(0).getId();
        }
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
        tripReplanJobService.process(job.getId());
        return job.getId();
    }

    private ReplanJobStatusResponse toStatusResponse(TripReplanJob job) {
        TripReplanResponse result = null;
        if (job.getStatus() == JobStatus.DONE && job.getResultJson() != null) {
            try {
                TripReplanGraph.ReplanOutcome outcome =
                        MAPPER.readValue(job.getResultJson(), TripReplanGraph.ReplanOutcome.class);
                result = TripReplanResponse.from(outcome);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("저장된 재구성 결과를 읽을 수 없습니다: jobId=" + job.getId(), e);
            }
        }
        return new ReplanJobStatusResponse(
                job.getStatus().name(), job.getCompletedTargets(), job.getTotalTargets(), result, job.getErrorMessage());
    }
}
