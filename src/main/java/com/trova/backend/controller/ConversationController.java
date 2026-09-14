package com.trova.backend.controller;

import com.trova.backend.conversation.ConversationService;
import com.trova.backend.conversation.ConversationSessionStore;
import com.trova.backend.conversation.ConversationState;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ConversationController {

    private static final int MAX_MESSAGE_LENGTH = 300;

    private final CurrentUserService currentUserService;
    private final ConversationSessionStore sessionStore;
    private final ConversationService conversationService;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;

    public ConversationController(
            CurrentUserService currentUserService,
            ConversationSessionStore sessionStore,
            ConversationService conversationService,
            TripPlaceRepository tripPlaceRepository,
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository
    ) {
        this.currentUserService = currentUserService;
        this.sessionStore = sessionStore;
        this.conversationService = conversationService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
    }

    public record ConversationMessageRequest(
            String message, Long tripId, Long tripPlaceId, Integer day, Long gapBeforePlaceId
    ) {
    }

    public record CandidateResponse(
            Long placeId, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, Double latitude, Double longitude, String address,
            Double distanceToNextKm, Integer estimatedTravelMinutes,
            Boolean isCongestionAvailable, String congestionLevel, String recommendationReason
    ) {
        static CandidateResponse from(AlternativeCandidate c) {
            return new CandidateResponse(
                    c.placeId(), c.googlePlaceId(), c.name(), c.category(), c.rating(), c.userRatingCount(),
                    c.latitude(), c.longitude(), c.address(), c.distanceToNextKm(), c.estimatedTravelMinutes(),
                    c.isCongestionAvailable(), c.congestionLevel(), c.recommendationReason());
        }
    }

    public record ConversationMessageResponse(
            String reply, List<CandidateResponse> candidates, int turnCount, boolean turnLimitReached
    ) {
    }

    @PostMapping("/api/conversations/{sessionId}/messages")
    public ResponseEntity<ConversationMessageResponse> sendMessage(
            Authentication authentication, @PathVariable String sessionId,
            @RequestBody ConversationMessageRequest request
    ) {
        User user = currentUserService.resolve(authentication);

        if (request.message() == null || request.message().isBlank()
                || request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().build();
        }

        ConversationState state = sessionStore.get(sessionId);
        if (state == null) {
            boolean hasPlaceContext = request.tripPlaceId() != null;
            boolean hasGapContext = request.day() != null && request.gapBeforePlaceId() != null;
            if (hasPlaceContext == hasGapContext) {
                // 세션은 정확히 하나의 컨텍스트(장소 또는 빈 시간 구간)에만 묶인다 —
                // 스펙 "이미 확정된 것" 절.
                return ResponseEntity.badRequest().build();
            }
            state = hasPlaceContext
                    ? createPlaceSession(user, sessionId, request)
                    : createGapSession(user, sessionId, request);
            if (state == null) {
                return ResponseEntity.notFound().build();
            }
        }

        ConversationService.TurnResult result = conversationService.sendMessage(user, state, request.message());
        List<CandidateResponse> candidateResponses = result.candidates() == null
                ? null
                : result.candidates().stream().map(CandidateResponse::from).toList();
        return ResponseEntity.ok(new ConversationMessageResponse(
                result.reply(), candidateResponses, result.turnCount(), result.turnLimitReached()));
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Void> endSession(Authentication authentication, @PathVariable String sessionId) {
        currentUserService.resolve(authentication);
        sessionStore.remove(sessionId);
        return ResponseEntity.noContent().build();
    }

    private ConversationState createPlaceSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripPlaceRepository.findById(request.tripPlaceId())
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .isPresent();
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), request.tripPlaceId(), null, null)
                : null;
    }

    private ConversationState createGapSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripRepository.findById(request.tripId())
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .flatMap(t -> itineraryRepository.findByTripAndDay(t, request.day()))
                .isPresent();
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), null, request.day(), request.gapBeforePlaceId())
                : null;
    }
}
