package com.trova.backend.controller;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.ReviewSummary;
import com.trova.backend.recommendation.PlaceReviewService;
import com.trova.backend.recommendation.PlaceSearchService;
import com.trova.backend.recommendation.RecommendationService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.service.CurrentUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RecommendationController {

    private static final double DEFAULT_RADIUS_METERS = 1000.0;
    private static final double MAX_RADIUS_METERS = 50_000.0;

    public record RecommendRequest(Double latitude, Double longitude, Double radiusMeters) {
    }

    public record PlaceRecommendationResponse(
            Long id, String googlePlaceId, String name, String category, String mood, String space,
            Double rating, Integer userRatingCount, String priceLevel,
            Double latitude, Double longitude, String address
    ) {
        static PlaceRecommendationResponse from(Place place) {
            return new PlaceRecommendationResponse(
                    place.getId(), place.getGooglePlaceId(), place.getName(), place.getCategory(),
                    place.getMood(), place.getSpace(), place.getRating(), place.getUserRatingCount(),
                    place.getPriceLevel(), place.getLatitude(), place.getLongitude(), place.getAddress());
        }
    }

    public record PlaceDetailResponse(
            Long id, String googlePlaceId, String name, String category,
            Double rating, Integer userRatingCount, String priceLevel,
            Double latitude, Double longitude, String address,
            String highlights, List<String> pros, List<String> cons,
            String hours, String fee, List<String> tips, List<String> checklist,
            List<String> reviewSnippets
    ) {
        static PlaceDetailResponse from(Place place, PlaceReviewService.PlaceReviewInfo reviewInfo) {
            ReviewSummary summary = reviewInfo.summary();
            return new PlaceDetailResponse(
                    place.getId(), place.getGooglePlaceId(), place.getName(), place.getCategory(),
                    place.getRating(), place.getUserRatingCount(), place.getPriceLevel(),
                    place.getLatitude(), place.getLongitude(), place.getAddress(),
                    summary.highlights(), summary.pros(), summary.cons(),
                    summary.hours(), summary.fee(), summary.tips(), summary.checklist(),
                    reviewInfo.snippets());
        }
    }

    private final RecommendationService recommendationService;
    private final CurrentUserService currentUserService;
    private final PlaceSearchService placeSearchService;
    private final PlaceReviewService placeReviewService;
    private final PlaceRepository placeRepository;

    public RecommendationController(
            RecommendationService recommendationService,
            CurrentUserService currentUserService,
            PlaceSearchService placeSearchService,
            PlaceReviewService placeReviewService,
            PlaceRepository placeRepository
    ) {
        this.recommendationService = recommendationService;
        this.currentUserService = currentUserService;
        this.placeSearchService = placeSearchService;
        this.placeReviewService = placeReviewService;
        this.placeRepository = placeRepository;
    }

    @PostMapping("/api/recommendations")
    public ResponseEntity<?> recommend(
            OAuth2AuthenticationToken authentication, @RequestBody RecommendRequest request
    ) {
        if (request == null || request.latitude() == null || request.longitude() == null) {
            return ResponseEntity.badRequest().build();
        }
        double radius = request.radiusMeters() != null ? request.radiusMeters() : DEFAULT_RADIUS_METERS;
        if (radius <= 0 || radius > MAX_RADIUS_METERS) {
            return ResponseEntity.badRequest().build();
        }

        User user = currentUserService.resolve(authentication);
        List<Place> places = recommendationService.recommend(user, request.latitude(), request.longitude(), radius);
        return ResponseEntity.ok(places.stream().map(PlaceRecommendationResponse::from).toList());
    }

    @GetMapping("/api/places/search")
    public ResponseEntity<List<PlaceRecommendationResponse>> search(@RequestParam String query) {
        if (query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<Place> places = placeSearchService.search(query);
        return ResponseEntity.ok(places.stream().map(PlaceRecommendationResponse::from).toList());
    }

    @GetMapping("/api/places/{id}/details")
    public ResponseEntity<PlaceDetailResponse> details(@PathVariable Long id) {
        return placeRepository.findById(id)
                .flatMap(place -> placeReviewService.getOrGenerateSummary(id)
                        .map(reviewInfo -> PlaceDetailResponse.from(place, reviewInfo)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
