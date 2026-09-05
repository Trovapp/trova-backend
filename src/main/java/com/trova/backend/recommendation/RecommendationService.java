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
 * Google Places 근처 검색 → 후보 upsert(배치, N+1 방지) → 하드필터+스코어링으로 퍼널
 * → 아직 안 태깅된 것만 Gemini로 배치 태깅(요청당 최대 1회 호출) → 최종 상위 N개 반환.
 *
 * Plan B의 퍼널+병렬 구조를 참고했지만, 태깅은 병렬 개별 호출이 아니라 배치 1회
 * 호출로 바꿨다(Trova는 Gemini 무료 티어 RPM이 낮아서 후보마다 동시 호출하면 429
 * 위험이 큼 — 0-1 원칙: 그대로 베끼지 않고 이 프로젝트 상황에 맞게 재설계).
 */
@Service
public class RecommendationService {

    // Plan B에서 그대로 가져온 값 — Trova 실사용 데이터로 재검증한 적 없음(0-5 원칙).
    private static final int MIN_REVIEW_COUNT = 1;
    private static final int FUNNEL_TOP_N = 7;
    private static final int FINAL_TOP_N = 5;
    // 북마크 선호점수를 최종 랭킹에 얼마나 반영할지 — 감으로 정함, 실측 아님(0-5 원칙).
    private static final double PREFERENCE_BOOST_WEIGHT = 0.5;

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceRepository placeRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final UserPreferenceRepository userPreferenceRepository;

    public RecommendationService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceRepository placeRepository,
            PlaceTaggingRunner placeTaggingRunner,
            UserPreferenceRepository userPreferenceRepository
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
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

        List<Place> upserted = upsert(rawCandidates);

        List<Place> funnel = upserted.stream()
                .filter(p -> p.getUserRatingCount() != null && p.getUserRatingCount() >= MIN_REVIEW_COUNT)
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(FUNNEL_TOP_N)
                .toList();

        tagMissing(funnel);

        // 태깅이 끝난 뒤에야 mood를 알 수 있어서, 선호도 반영 랭킹은 여기서만 가능하다.
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

    private List<Place> upsert(List<GooglePlacesNearbySearchResponse.Place> rawCandidates) {
        List<String> googleIds = rawCandidates.stream()
                .map(GooglePlacesNearbySearchResponse.Place::id)
                .toList();
        Map<String, Place> existingByGoogleId = placeRepository.findByGooglePlaceIdIn(googleIds).stream()
                .collect(Collectors.toMap(Place::getGooglePlaceId, p -> p));

        List<Place> result = new ArrayList<>();
        for (GooglePlacesNearbySearchResponse.Place raw : rawCandidates) {
            Place existing = existingByGoogleId.get(raw.id());
            if (existing != null) {
                result.add(existing);
                continue;
            }
            String category = raw.types() != null && !raw.types().isEmpty() ? raw.types().get(0) : null;
            String name = raw.displayName() != null ? raw.displayName().text() : null;
            Double lat = raw.location() != null ? raw.location().latitude() : null;
            Double lng = raw.location() != null ? raw.location().longitude() : null;
            Place created = placeRepository.save(new Place(
                    raw.id(), name, category, raw.rating(), raw.userRatingCount(),
                    raw.priceLevel(), lat, lng, raw.formattedAddress()));
            result.add(created);
        }
        return result;
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
