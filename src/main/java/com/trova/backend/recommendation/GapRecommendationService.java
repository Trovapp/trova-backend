package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.User;
import com.trova.backend.repository.GapPlaceRow;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시간이 입력된 연속 장소 사이에 30분 넘는 공백이 있으면, 두 장소의 중간지점
 * 기준으로 갈 만한 곳을 추천한다. 시간이 하나라도 비어있는 쌍은 건너뛴다 —
 * 임의로 시간을 추정하지 않는다.
 */
@Service
public class GapRecommendationService {

    private static final Duration GAP_THRESHOLD = Duration.ofMinutes(30);
    static final LocalTime DAY_END = LocalTime.of(21, 0);
    private static final LocalTime LUNCH_FROM = LocalTime.of(11, 0);
    private static final LocalTime LUNCH_TO = LocalTime.of(14, 0);
    private static final LocalTime DINNER_FROM = LocalTime.of(17, 0);
    private static final LocalTime DINNER_TO = LocalTime.of(20, 0);
    private static final double SEARCH_RADIUS_METERS = 1500;
    // AlternativeFinderService와 동일한 이유로 곱셈 부스트 + 바닥값을 쓴다
    // (2026-09-14 실측 — 덧셈 부스트는 인기 랜드마크 baseScore 앞에서 무의미했음).
    private static final double PERSONALIZATION_BOOST_WEIGHT = 0.6;
    private static final double MIN_BASE_SCORE_FOR_BOOST = 1.0;

    public record Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations) {
    }

    private final SavedPlaceRepository savedPlaceRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final ApiCallLogService apiCallLogService;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final PersonalizationService personalizationService;

    public GapRecommendationService(
            SavedPlaceRepository savedPlaceRepository, TripPlaceRepository tripPlaceRepository,
            GooglePlacesApiClient googlePlacesApiClient, PlaceCatalogService placeCatalogService, ApiCallLogService apiCallLogService,
            PlaceEmbeddingService placeEmbeddingService, PersonalizationService personalizationService
    ) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.apiCallLogService = apiCallLogService;
        this.placeEmbeddingService = placeEmbeddingService;
        this.personalizationService = personalizationService;
    }

    public Optional<List<Gap>> findGaps(User user, Long tripId, int day) {
        return findGaps(user.getId(), tripId, day);
    }

    /**
     * 남의 여행·없는 여행·없는 일차는 빈 값. 일차와 장소는 소유자 조건을 건 조회 한 번으로 읽는다(#482) — 예전에는
     * 여행·일차·장소 엔티티와 기본 EAGER 연관(일차의 여행, 장소의 일차)을 따로 읽어 SQL이 5~6번이었다.
     */
    public Optional<List<Gap>> findGaps(Long userId, Long tripId, int day) {
        List<GapPlaceRow> rows = tripPlaceRepository.findGapPlaceRows(tripId, userId, day);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long itineraryCount = rows.stream().map(GapPlaceRow::itineraryId).distinct().count();
        if (itineraryCount > 1) {
            // 이전 findByTripAndDay(Optional)와 같이 같은 일차가 둘이면 결과 하나를 고르지 않고 실패한다.
            throw new IncorrectResultSizeDataAccessException(1, (int) itineraryCount);
        }
        List<GapPlaceRow> places = rows.stream().filter(row -> row.placeId() != null).toList();
        return Optional.of(computeGaps(places, userId));
    }

    private List<Gap> computeGaps(List<GapPlaceRow> places, Long userId) {
        List<Gap> gaps = new ArrayList<>();

        for (int i = 0; i < places.size() - 1; i++) {
            GapPlaceRow before = places.get(i);
            GapPlaceRow after = places.get(i + 1);
            LocalTime endTime = before.visitEndTime();
            LocalTime startTime = after.visitStartTime();
            if (endTime == null || startTime == null) {
                continue;
            }
            Duration gap = Duration.between(endTime, startTime);
            if (gap.compareTo(GAP_THRESHOLD) <= 0) {
                continue;
            }
            if (before.latitude() == null || before.longitude() == null
                    || after.latitude() == null || after.longitude() == null) {
                continue;
            }
            double midLat = (before.latitude() + after.latitude()) / 2;
            double midLng = (before.longitude() + after.longitude()) / 2;
            gaps.add(new Gap(before.placeId(), after.placeId(), (int) gap.toMinutes(),
                    recommend(userId, midLat, midLng, endTime, startTime, List.of(before, after))));
        }

        // 마지막 장소 뒤 ~ 하루 끝(21:00)도 빈 시간이다 — 장소 사이만 봐서 09:00~10:00 한 곳뿐인 날에
        // "채울 빈 시간이 없어요"가 떴다(안드로이드 사용성 테스트 2026-11-12, #414). 마지막 장소 뒤에 넣으면 되므로
        // 기존 "뒤에 넣기"(insertPlaceAfter)를 그대로 쓴다.
        if (!places.isEmpty()) {
            GapPlaceRow last = places.get(places.size() - 1);
            LocalTime endTime = last.visitEndTime();
            if (endTime != null && endTime.isBefore(DAY_END) && last.latitude() != null && last.longitude() != null) {
                Duration gap = Duration.between(endTime, DAY_END);
                if (gap.compareTo(GAP_THRESHOLD) > 0) {
                    gaps.add(new Gap(last.placeId(), null, (int) gap.toMinutes(),
                            recommend(userId, last.latitude(), last.longitude(), endTime, DAY_END, List.of(last))));
                }
            }
        }
        return gaps;
    }

    private List<AlternativeCandidate> recommend(Long userId, double lat, double lng, LocalTime from, LocalTime to,
                                                 List<GapPlaceRow> neighbors) {
        // 점심·저녁 시간대가 걸리면 식당부터 찾는다 — 점심 시간 빈칸에 호텔·역·대학교가 20개 넘게 섞여 나왔다(#414).
        String includedType = overlaps(from, to, LUNCH_FROM, LUNCH_TO) || overlaps(from, to, DINNER_FROM, DINNER_TO)
                ? "restaurant" : null;
        // 검색 실패를 500으로 흘려보내지 않는다 — AlternativeFinderService와 같은 원칙.
        long searchStart = System.currentTimeMillis();
        GooglePlacesNearbySearchResponse response;
        try {
            response = googlePlacesApiClient.searchNearby(lat, lng, SEARCH_RADIUS_METERS, includedType);
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - searchStart, true, null, null, null, null);
        } catch (Exception e) {
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - searchStart, false, e.getMessage(), null, null, null);
            response = new GooglePlacesNearbySearchResponse(List.of());
        }
        List<GooglePlacesNearbySearchResponse.Place> raw =
                response.places() != null ? response.places() : List.of();
        List<Place> candidates = raw.isEmpty() ? List.of() : placeCatalogService.upsertAll(raw);
        Map<String, List<String>> kakaoCategories = new HashMap<>();
        if (!raw.isEmpty()) {
            var ids = raw.stream().map(GooglePlacesNearbySearchResponse.Place::id).distinct().toList();
            for (var row : savedPlaceRepository.findKakaoCategories(userId, ids)) {
                kakaoCategories.computeIfAbsent(row.googlePlaceId(), ignored -> new ArrayList<>()).add(row.category());
            }
        }
        // upsertAll은 검색 순서(중복 ID 포함)대로 카탈로그 행을 돌려준다.
        List<Place> filteredCandidates = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Place candidate = candidates.get(i);
            var source = raw.get(i);
            if (neighbors.stream().noneMatch(n -> isSameGooglePlace(candidate, n))
                    && GapStopPolicy.allows(source, candidate, kakaoCategories.getOrDefault(source.id(), List.of()))) {
                filteredCandidates.add(candidate);
            }
        }
        placeEmbeddingService.ensureEmbeddings(filteredCandidates);
        // Comparator 안에서 점수를 계산하면 정렬 비교마다(O(n log n)회) 매번 다시 계산된다 — 후보당 한 번만
        // 계산해 맵에 담아두고 정렬은 맵 조회만 한다. 개인화 점수는 후보마다 pgvector를 2번씩 조회하던 것을
        // 후보 전체에 대해 한 번으로 묶었다(#482, 운영 요청당 SQL 평균 20회의 대부분).
        Map<Long, Double> personalization = personalizationService.personalizationScores(userId, filteredCandidates);
        Map<Long, Double> scoreByPlaceId = new HashMap<>();
        for (Place c : filteredCandidates) {
            double score = Math.max(PlaceScoring.baseScore(c), MIN_BASE_SCORE_FOR_BOOST)
                    * (1 + personalization.getOrDefault(c.getId(), 0.0) * PERSONALIZATION_BOOST_WEIGHT);
            scoreByPlaceId.put(c.getId(), score);
        }
        return filteredCandidates.stream()
                // 역순 comparingDouble은 NaN을 맨 앞에 둔다. 무효 점수는 별도로 뒤에 둔다(#487).
                .sorted(Comparator.comparingInt((Place c) -> Double.isFinite(scoreByPlaceId.get(c.getId())) ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble((Place c) -> scoreByPlaceId.get(c.getId())).reversed()))
                .map(c -> new AlternativeCandidate(
                        c.getId(), c.getGooglePlaceId(), c.getName(), c.getCategory(), c.getRating(),
                        c.getUserRatingCount(), c.getLatitude(), c.getLongitude(), c.getAddress(),
                        null, null, false, null, null))
                .toList();
    }

    private static boolean overlaps(LocalTime from, LocalTime to, LocalTime windowFrom, LocalTime windowTo) {
        return from.isBefore(windowTo) && to.isAfter(windowFrom);
    }

    private boolean isSameGooglePlace(Place candidate, GapPlaceRow tripPlace) {
        return tripPlace.googlePlaceId() != null
                && tripPlace.googlePlaceId().equals(candidate.getGooglePlaceId());
    }
}
