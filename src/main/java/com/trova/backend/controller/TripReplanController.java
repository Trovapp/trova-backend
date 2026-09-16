package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TripReplanController {

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final TripReplanGraph tripReplanGraph;

    public TripReplanController(
            CurrentUserService currentUserService,
            TripRepository tripRepository,
            TripReplanGraph tripReplanGraph
    ) {
        this.currentUserService = currentUserService;
        this.tripRepository = tripRepository;
        this.tripReplanGraph = tripReplanGraph;
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

    @PostMapping("/api/trips/{tripId}/replan")
    public ResponseEntity<TripReplanResponse> replan(
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
                .map(trip -> {
                    TripReplanGraph.ReplanOutcome outcome = tripReplanGraph.run(user, trip, true);
                    return ResponseEntity.ok(TripReplanResponse.from(outcome));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
