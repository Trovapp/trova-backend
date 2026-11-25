package com.trova.backend.controller;

import com.trova.backend.service.TripAccess;
import com.trova.backend.entity.Trip;
import com.trova.backend.conversation.ConversationService;
import com.trova.backend.conversation.ConversationStreams;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.swagger.v3.oas.annotations.Operation;
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
    private final ConversationStreams streams;
    private final TripPlaceRepository tripPlaceRepository;
    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;

    public ConversationController(
            CurrentUserService currentUserService,
            ConversationSessionStore sessionStore,
            ConversationService conversationService,
            TripPlaceRepository tripPlaceRepository,
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository,
            ConversationStreams streams
    ) {
        this.streams = streams;
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
        public static CandidateResponse from(AlternativeCandidate c) {
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

        ResponseEntity<ConversationState> prepared = prepareSession(user, sessionId, request);
        if (!prepared.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(prepared.getStatusCode()).build();
        }
        ConversationState state = prepared.getBody();

        ConversationService.TurnResult result = conversationService.sendMessage(user, state, request.message());
        List<CandidateResponse> candidateResponses = result.candidates() == null
                ? null
                : result.candidates().stream().map(CandidateResponse::from).toList();
        return ResponseEntity.ok(new ConversationMessageResponse(
                result.reply(), candidateResponses, result.turnCount(), result.turnLimitReached()));
    }

    @Operation(summary = "장소 카드를 먼저 보내는 대화 SSE",
            description = "인증된 POST. cards {candidates:[기존 CandidateResponse]}, reply {reply,turnCount,turnLimitReached}, done {turnCount,turnLimitReached} 순서. "
                    + "도구/후보가 없으면 reply,done. 시작 후 실패는 error {timestamp,status,error,code,message,path,traceId} 후 종료(done 없음). "
                    + "상세 계약: docs/api/conversation-stream.md. 자동 재연결·재전송 금지.")
    @PostMapping(value = "/api/conversations/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamMessage(Authentication authentication, @PathVariable String sessionId,
            @RequestBody ConversationMessageRequest request, HttpServletRequest servletRequest) {
        User user = currentUserService.resolve(authentication);
        ResponseEntity<ConversationState> prepared = prepareSession(user, sessionId, request);
        if (!prepared.getStatusCode().is2xxSuccessful()) {
            throw new org.springframework.web.server.ResponseStatusException(prepared.getStatusCode());
        }
        ConversationStreams.Pending pending = streams.prepare(user, prepared.getBody(), request.message(), servletRequest);
        servletRequest.setAttribute(ConversationStreams.PENDING, pending);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").header("X-Trace-Id", pending.traceId()).body(pending.emitter());
    }

    private ResponseEntity<ConversationState> prepareSession(User user, String sessionId, ConversationMessageRequest request) {
        if (request.message() == null || request.message().isBlank()
                || request.message().length() > MAX_MESSAGE_LENGTH) {
            return ResponseEntity.badRequest().build();
        }

        ConversationState state = sessionStore.get(sessionId);
        if (state != null && !state.getUserId().equals(user.getId())) {
            // sessionId를 알아낸/재사용한 비소유자에게 세션 존재 여부를 확인해주지
            // 않기 위해 403이 아닌 404로 응답한다.
            return ResponseEntity.notFound().build();
        }
        if (state != null) {
            Trip trip = tripRepository.findById(state.getTripId()).orElse(null);
            if (trip == null) return ResponseEntity.notFound().build();
            TripAccess.requireMember(trip, user);
        }
        if (state == null) {
            boolean hasPlaceContext = request.tripPlaceId() != null;
            boolean hasGapContext = request.day() != null && request.gapBeforePlaceId() != null;
            if (hasPlaceContext == hasGapContext || request.tripId() == null) {
                // 세션은 정확히 하나의 컨텍스트(장소 또는 빈 시간 구간)에만 묶인다 —
                // 스펙 "이미 확정된 것" 절. tripId는 두 컨텍스트 모두에 필요하다.
                return ResponseEntity.badRequest().build();
            }
            state = hasPlaceContext
                    ? createPlaceSession(user, sessionId, request)
                    : createGapSession(user, sessionId, request);
            if (state == null) {
                return ResponseEntity.notFound().build();
            }
        }

        return ResponseEntity.ok(state);
    }

    @DeleteMapping("/api/conversations/{sessionId}")
    public ResponseEntity<Void> endSession(Authentication authentication, @PathVariable String sessionId) {
        User user = currentUserService.resolve(authentication);
        ConversationState state = sessionStore.get(sessionId);
        if (state != null && !state.getUserId().equals(user.getId())) {
            // 비소유자에게 세션 존재 여부를 확인해주지 않기 위해 404로 응답하고,
            // 소유자가 아닌 세션은 삭제하지 않는다.
            return ResponseEntity.notFound().build();
        }
        sessionStore.remove(sessionId);
        return ResponseEntity.noContent().build();
    }

    private ConversationState createPlaceSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripPlaceRepository.findById(request.tripPlaceId())
                .filter(p -> p.getItinerary().getTrip().getId().equals(request.tripId()))
                .filter(p -> TripAccess.requireMember(p.getItinerary().getTrip(), user))
                .isPresent();
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), request.tripPlaceId(), null, null)
                : null;
    }

    private ConversationState createGapSession(User user, String sessionId, ConversationMessageRequest request) {
        boolean owned = tripRepository.findById(request.tripId())
                .filter(t -> TripAccess.requireMember(t, user))
                .flatMap(t -> itineraryRepository.findByTripAndDay(t, request.day()))
                .isPresent();
        if (owned) {
            owned = tripPlaceRepository.findById(request.gapBeforePlaceId()).filter(place ->
                    place.getItinerary().getTrip().getId().equals(request.tripId())
                            && place.getItinerary().getDay() == request.day()).isPresent();
        }
        return owned
                ? sessionStore.create(sessionId, user.getId(), request.tripId(), null, request.day(), request.gapBeforePlaceId())
                : null;
    }
}
