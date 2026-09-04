package com.trova.backend.controller;

import com.trova.backend.entity.Place;
import com.trova.backend.recommendation.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @PostMapping("/api/recommendations")
    public ResponseEntity<?> recommend(@RequestBody RecommendRequest request) {
        if (request == null || request.latitude() == null || request.longitude() == null) {
            return ResponseEntity.badRequest().build();
        }
        double radius = request.radiusMeters() != null ? request.radiusMeters() : DEFAULT_RADIUS_METERS;
        if (radius <= 0 || radius > MAX_RADIUS_METERS) {
            return ResponseEntity.badRequest().build();
        }

        List<Place> places = recommendationService.recommend(request.latitude(), request.longitude(), radius);
        return ResponseEntity.ok(places.stream().map(PlaceRecommendationResponse::from).toList());
    }
}
