package com.trova.backend.controller;

import com.trova.backend.service.DailyQuotaService;
import com.trova.backend.entity.*;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.PlaceReviewService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.PlanService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.WeatherRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class TripController {

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");

    private final CurrentUserService currentUserService;
    private final PlanService planService;
    private final DailyQuotaService dailyQuotaService;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final TripService tripService;
    private final ItineraryRepository itineraryRepository;
    private final TripRepository tripRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final WeatherRecoveryService weatherRecoveryService;
    private final PlaceReviewService placeReviewService;
    private final AlternativeFinderService alternativeFinderService;
    private final GapRecommendationService gapRecommendationService;

    public TripController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            TripService tripService,
            ItineraryRepository itineraryRepository,
            TripRepository tripRepository,
            TripPlaceRepository tripPlaceRepository,
            WeatherRecoveryService weatherRecoveryService,
            PlaceReviewService placeReviewService,
            AlternativeFinderService alternativeFinderService,
            GapRecommendationService gapRecommendationService,
            DailyQuotaService dailyQuotaService,
            PlanService planService
    ) {
        this.currentUserService = currentUserService;
        this.dailyQuotaService = dailyQuotaService;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripService = tripService;
        this.itineraryRepository = itineraryRepository;
        this.tripRepository = tripRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.weatherRecoveryService = weatherRecoveryService;
        this.placeReviewService = placeReviewService;
        this.alternativeFinderService = alternativeFinderService;
        this.gapRecommendationService = gapRecommendationService;
        this.planService = planService;
    }

    // 앱(trova-app TRIP_TITLE_MAX_LENGTH)과 같은 제한. 서버가 막지 않으면 DB varchar(255)를 넘는 요청이
    // 500으로 터졌다(#23).
    static final int TRIP_TITLE_MAX_LENGTH = 50;
    // 여행은 하루마다 일정(Itinerary) 행을 만든다 — 상한이 없으면 3년짜리 여행 한 번에 1,096행이 생겼다(#25).
    static final int TRIP_MAX_DAYS = 30;

    private static boolean isValidTripTitle(String title) {
        return title != null && !title.isBlank() && title.trim().length() <= TRIP_TITLE_MAX_LENGTH;
    }

    public record ConfirmTripRequest(String title, LocalDate startDate) {
    }

    public record CreateTripRequest(String title, LocalDate startDate, LocalDate endDate) {
    }

    public record AddPlaceRequest(String googlePlaceId) {
    }

    public record ReplaceRequest(String googlePlaceId) {
    }

    public record InsertRequest(Long afterTripPlaceId, String googlePlaceId) {
    }

    public record ReorderRequest(String direction) {
    }

    public record UpdateDetailsRequest(
            LocalTime visitStartTime, LocalTime visitEndTime, String arrivalTransportMode, String memo
    ) {
    }

    public record TripResponse(Long id, String title, LocalDate startDate, LocalDate endDate,
                               int placeCount, List<String> regions) {
        static TripResponse from(Trip trip) {
            return new TripResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(), 0, List.of());
        }

        static TripResponse from(Trip trip, List<TripPlace> places) {
            // 많이 나온 지역 순, 같으면 먼저 나온 지역 — 최대 2개("제주", "김해·부산").
            Map<String, Long> counts = places.stream()
                    .map(TripPlace::getRegion)
                    .filter(r -> r != null && !r.isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(r -> r, java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.counting()));
            List<String> regions = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(2)
                    .map(Map.Entry::getKey)
                    .toList();
            return new TripResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(),
                    places.size(), regions);
        }
    }

    public record TripPlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String phone, String address,
            int visitOrder, String source, String googlePlaceId,
            LocalTime visitStartTime, LocalTime visitEndTime, String arrivalTransportMode, String memo
    ) {
        static TripPlaceResponse from(TripPlace p) {
            return new TripPlaceResponse(
                    p.getId(), p.getPlaceName(), p.getRegion(), p.getCategory(),
                    p.getLatitude(), p.getLongitude(), p.getPhone(), p.getAddress(),
                    p.getVisitOrder(), p.getSource().name(), p.getGooglePlaceId(),
                    p.getVisitStartTime(), p.getVisitEndTime(),
                    p.getArrivalTransportMode() != null ? p.getArrivalTransportMode().name() : null,
                    p.getMemo());
        }
    }

    public record AlternativeCandidateResponse(
            Long placeId, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, Double latitude, Double longitude, String address,
            Double distanceToNextKm, Integer estimatedTravelMinutes,
            Boolean isCongestionAvailable, String congestionLevel, String recommendationReason
    ) {
        static AlternativeCandidateResponse from(com.trova.backend.recommendation.AlternativeCandidate c) {
            return new AlternativeCandidateResponse(
                    c.placeId(), c.googlePlaceId(), c.name(), c.category(), c.rating(), c.userRatingCount(),
                    c.latitude(), c.longitude(), c.address(), c.distanceToNextKm(), c.estimatedTravelMinutes(),
                    c.isCongestionAvailable(), c.congestionLevel(), c.recommendationReason());
        }
    }

    public record GapResponse(
            Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidateResponse> recommendations
    ) {
        static GapResponse from(GapRecommendationService.Gap gap) {
            return new GapResponse(
                    gap.beforePlaceId(), gap.afterPlaceId(), gap.gapMinutes(),
                    gap.recommendations().stream().map(AlternativeCandidateResponse::from).toList());
        }
    }

    public record ItineraryResponse(Long id, int day, LocalDate date, List<TripPlaceResponse> places) {
    }

    public record TripDetailResponse(
            Long id, String title, LocalDate startDate, LocalDate endDate, List<ItineraryResponse> days
    ) {
    }

    public record WeatherCheckResponse(boolean notified, String message) {
    }

    public record TripPlaceDetailResponse(
            Long id, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, String priceLevel,
            Double latitude, Double longitude, String address,
            String highlights, List<String> pros, List<String> cons,
            String hours, String fee, List<String> tips, List<String> checklist,
            List<String> reviewSnippets, String phone
    ) {
        static TripPlaceDetailResponse from(Place place, PlaceReviewService.PlaceReviewInfo reviewInfo) {
            ReviewSummary summary = reviewInfo.summary();
            return new TripPlaceDetailResponse(
                    place.getId(), place.getGooglePlaceId(), place.getName(), place.getCategory(),
                    place.getRating(), place.getUserRatingCount(), place.getPriceLevel(),
                    place.getLatitude(), place.getLongitude(), place.getAddress(),
                    summary.highlights(), summary.pros(), summary.cons(),
                    summary.hours(), summary.fee(), summary.tips(), summary.checklist(),
                    reviewInfo.snippets(), reviewInfo.phone());
        }
    }

    @PostMapping("/api/trips")
    public ResponseEntity<TripResponse> createTrip(
            Authentication authentication, @RequestBody CreateTripRequest request
    ) {
        if (request == null || !isValidTripTitle(request.title())
                || request.startDate() == null || request.endDate() == null
                || request.endDate().isBefore(request.startDate())
                || ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1 > TRIP_MAX_DAYS) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        Trip trip = tripService.createTrip(user, request.title(), request.startDate(), request.endDate());
        return ResponseEntity.ok(TripResponse.from(trip));
    }

    @GetMapping("/api/trips")
    public List<TripResponse> listTrips(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        List<Trip> trips = tripRepository.findByUserOrderByCreatedAtDesc(user);
        // 이름이 같은 여행을 목록에서 구분할 수 없었다(#123) — 장소 수와 가장 많이 나온 지역(최대 2개)을 함께 준다.
        Map<Long, List<TripPlace>> placesByTrip = trips.isEmpty() ? Map.of()
                : tripPlaceRepository.findByItineraryTripIn(trips).stream()
                        .collect(java.util.stream.Collectors.groupingBy(tp -> tp.getItinerary().getTrip().getId()));
        return trips.stream()
                .map(t -> TripResponse.from(t, placesByTrip.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @GetMapping("/api/trips/{id}")
    public ResponseEntity<TripDetailResponse> getTrip(
            Authentication authentication, @PathVariable Long id
    ) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findById(id)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    List<ItineraryResponse> days = itineraryRepository.findByTripOrderByDay(trip).stream()
                            .map(itinerary -> new ItineraryResponse(
                                    itinerary.getId(), itinerary.getDay(), itinerary.getDate(),
                                    tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary).stream()
                                            .map(TripPlaceResponse::from)
                                            .toList()))
                            .toList();
                    return ResponseEntity.ok(new TripDetailResponse(
                            trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(), days));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/trips/{id}")
    public ResponseEntity<Void> deleteTrip(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean deleted = tripService.deleteTrip(user, id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/trips/{tripId}/days/{day}/places")
    public ResponseEntity<TripPlaceResponse> addPlace(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day,
            @RequestBody AddPlaceRequest request
    ) {
        if (request == null || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.addPlaceToDay(user, tripId, day, request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record AddVideoPlaceRequest(Long savedPlaceId) {
    }

    /** 영상에서 찾은 장소 하나를 이 여행의 그 일차 끝에 담는다(#126). 영상 장소·여행이 본인 것이 아니면 404. */
    @PostMapping("/api/trips/{tripId}/days/{day}/video-places")
    public ResponseEntity<TripPlaceResponse> addVideoPlace(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day,
            @RequestBody AddVideoPlaceRequest request
    ) {
        if (request == null || request.savedPlaceId() == null) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.addVideoPlaceToDay(user, tripId, day, request.savedPlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trip-places/{id}/replace")
    public ResponseEntity<TripPlaceResponse> replacePlace(
            Authentication authentication, @PathVariable Long id, @RequestBody ReplaceRequest request
    ) {
        if (request == null || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.replacePlace(user, id, request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trip-places/insert")
    public ResponseEntity<TripPlaceResponse> insertPlace(
            Authentication authentication, @RequestBody InsertRequest request
    ) {
        if (request == null || request.afterTripPlaceId() == null
                || request.googlePlaceId() == null || request.googlePlaceId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.insertPlaceAfter(user, request.afterTripPlaceId(), request.googlePlaceId())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/trip-places/{id}")
    public ResponseEntity<Void> removePlace(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = tripService.removePlace(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/api/trip-places/{id}/order")
    public ResponseEntity<TripPlaceResponse> reorderPlace(
            Authentication authentication, @PathVariable Long id, @RequestBody ReorderRequest request
    ) {
        if (request == null || request.direction() == null || !VALID_DIRECTIONS.contains(request.direction())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.reorderPlace(user, id, request.direction())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/api/trip-places/{id}/details")
    public ResponseEntity<TripPlaceResponse> updateDetails(
            Authentication authentication, @PathVariable Long id, @RequestBody UpdateDetailsRequest request
    ) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        TransportMode transportMode = null;
        if (request.arrivalTransportMode() != null) {
            try {
                transportMode = TransportMode.valueOf(request.arrivalTransportMode());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        User user = currentUserService.resolve(authentication);
        return tripService.updateDetails(
                        user, id, request.visitStartTime(), request.visitEndTime(), transportMode, request.memo())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/trip-places/{id}/details")
    public ResponseEntity<TripPlaceDetailResponse> tripPlaceDetails(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        dailyQuotaService.consumePlaceCall(user);
        return tripService.resolveDetailsPlace(user, id)
                .flatMap(place -> placeReviewService.getOrGenerateSummary(place.getId())
                        .map(reviewInfo -> TripPlaceDetailResponse.from(place, reviewInfo)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 이 영상으로 이미 만든 여행 — 앱이 "여행으로 만들기" 대신 "만든 여행 보기"를 보여줄 때 쓴다. 없으면 404. */
    @GetMapping("/api/places/videos/{jobId}/trip")
    public ResponseEntity<TripResponse> getVideoTrip(Authentication authentication, @PathVariable Long jobId) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .flatMap(job -> tripService.findExistingTripForVideo(user, job))
                .map(trip -> ResponseEntity.ok(TripResponse.from(trip)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/places/videos/{jobId}/confirm-trip")
    public ResponseEntity<TripResponse> confirmTrip(
            Authentication authentication, @PathVariable Long jobId, @RequestBody ConfirmTripRequest request
    ) {
        if (request == null || !isValidTripTitle(request.title())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> {
                    List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);
                    // 같은 영상을 다시 추출한 작업이어도 이미 만든 여행이 있으면 새로 만들지 않는다(#19).
                    Trip trip = tripService.findExistingTripForVideo(user, job)
                            .orElseGet(() -> tripService.confirmVideoPlacesIntoTrip(
                                    user, request.title(), places, request.startDate()));
                    return ResponseEntity.ok(TripResponse.from(trip));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trips/{tripId}/days/{day}/optimize-route")
    public ResponseEntity<List<TripPlaceResponse>> optimizeRoute(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        return tripService.optimizeRoute(user, tripId, day)
                .map(places -> ResponseEntity.ok(places.stream().map(TripPlaceResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trips/{tripId}/days/{day}/weather-check")
    public ResponseEntity<WeatherCheckResponse> weatherCheck(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(itinerary -> {
                    boolean notified = weatherRecoveryService.checkAndNotify(itinerary).isPresent();
                    String message = notified ? "비 소식이 있어 알림을 만들었어요" : "알림을 만들 조건이 아니에요(비 소식 없음/실외 장소 없음/이미 알림 있음)";
                    return ResponseEntity.ok(new WeatherCheckResponse(notified, message));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/trip-places/{id}/alternatives")
    public ResponseEntity<List<AlternativeCandidateResponse>> findAlternatives(
            Authentication authentication, @PathVariable Long id,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean indoor,
            @RequestParam(required = false) Double maxDistanceKm,
            @RequestParam(required = false) Integer maxTravelMinutes,
            @RequestParam(required = false) String transportMode
    ) {
        User user = currentUserService.resolve(authentication);
        planService.checkAndRecordAssist(user);
        dailyQuotaService.consumePlaceCall(user);
        TransportMode mode = null;
        if (transportMode != null) {
            try {
                mode = TransportMode.valueOf(transportMode);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        var filter = new com.trova.backend.recommendation.AlternativeFilter(
                category, indoor, maxDistanceKm, maxTravelMinutes, mode);
        return alternativeFinderService.findAlternatives(user, id, filter)
                .map(candidates -> ResponseEntity.ok(candidates.stream().map(AlternativeCandidateResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/trips/{tripId}/days/{day}/gap-recommendations")
    public ResponseEntity<List<GapResponse>> gapRecommendations(
            Authentication authentication, @PathVariable Long tripId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        // 빈 시간 추천은 여행 상세를 열 때 일차마다 자동으로 불린다 — 사용자가 누른 게 아니라 무료·패스 횟수로 세지 않는다(#130 QA).
        dailyQuotaService.consumePlaceCall(user);
        return gapRecommendationService.findGaps(user, tripId, day)
                .map(gaps -> ResponseEntity.ok(gaps.stream().map(GapResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
