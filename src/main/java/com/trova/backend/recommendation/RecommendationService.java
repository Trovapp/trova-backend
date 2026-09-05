package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.entity.UserPreference;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Places 근처 검색 → 후보 upsert(PlaceCatalogService, N+1 방지) → 하드필터+스코어링으로
 * 퍼널 → 아직 안 태깅된 것만 Gemini로 배치 태깅(요청당 최대 1회 호출) → 최종 상위 N개 반환.
 */
@Service
public class RecommendationService {

    private static final int MIN_REVIEW_COUNT = 1;
    private static final int FUNNEL_TOP_N = 7;
    private static final int FINAL_TOP_N = 5;
    private static final double PREFERENCE_BOOST_WEIGHT = 0.5;

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final PlaceRepository placeRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final UserPreferenceRepository userPreferenceRepository;

    public RecommendationService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            PlaceRepository placeRepository,
            PlaceTaggingRunner placeTaggingRunner,
            UserPreferenceRepository userPreferenceRepository
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.placeRepository = placeRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.userPreferenceRepository = userPreferenceRepository;
    }

    public List<Place> recommend(User user, double latitude, double longitude, double radiusMeters) {
        List<GooglePlacesNearbySearchResponse.Place> rawCandidates =
                googlePlacesApiClient.searchNearby(latitude, longitude, radiusMeters).places();
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return List.of();
        }

        List<Place> upserted = placeCatalogService.upsertAll(rawCandidates);

        List<Place> funnel = upserted.stream()
                .filter(p -> p.getUserRatingCount() != null && p.getUserRatingCount() >= MIN_REVIEW_COUNT)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(FUNNEL_TOP_N)
                .toList();

        tagMissing(funnel);

        Map<String, Double> preferenceByMood = userPreferenceRepository.findByUser(user).stream()
                .collect(Collectors.toMap(UserPreference::getMood, UserPreference::getScore));

        return funnel.stream()
                .sorted(Comparator.comparingDouble((Place p) -> scoreWithPreference(p, preferenceByMood)).reversed())
                .limit(FINAL_TOP_N)
                .toList();
    }

    private double scoreWithPreference(Place place, Map<String, Double> preferenceByMood) {
        double base = score(place);
        double preference = place.getMood() != null ? preferenceByMood.getOrDefault(place.getMood(), 0.0) : 0.0;
        return base + preference * PREFERENCE_BOOST_WEIGHT;
    }

    private void tagMissing(List<Place> funnel) {
        List<Place> needsTagging = funnel.stream().filter(p -> p.getMood() == null).toList();
        if (needsTagging.isEmpty()) {
            return;
        }

        List<PlaceTaggingRunner.TagCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < needsTagging.size(); i++) {
            Place p = needsTagging.get(i);
            candidates.add(new PlaceTaggingRunner.TagCandidate(
                    i, p.getName(), p.getCategory(), p.getRating(), p.getUserRatingCount(), p.getPriceLevel()));
        }

        long requestId = System.nanoTime();
        List<PlaceTag> tags = placeTaggingRunner.run(candidates, requestId);
        Map<Integer, PlaceTag> tagByIndex = tags.stream()
                .collect(Collectors.toMap(PlaceTag::index, t -> t));

        for (int i = 0; i < needsTagging.size(); i++) {
            PlaceTag tag = tagByIndex.get(i);
            if (tag == null) {
                continue;
            }
            Place p = needsTagging.get(i);
            p.applyTags(tag.mood(), tag.space());
            placeRepository.save(p);
        }
    }

    private double score(Place place) {
        double rating = place.getRating() != null ? place.getRating() : 0.0;
        int reviewCount = place.getUserRatingCount() != null ? place.getUserRatingCount() : 0;
        return rating * Math.log(reviewCount + 1);
    }
}
