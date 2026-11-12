package com.trova.backend.recommendation;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.ApiCallLogService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    static final Set<String> NOT_A_STOP = Set.of(
            "lodging", "hotel", "motel", "hostel", "guest_house", "resort_hotel", "extended_stay_hotel",
            "subway_station", "train_station", "transit_station", "bus_station", "light_rail_station", "airport",
            "university", "school", "primary_school", "secondary_school", "parking", "gas_station");
    private static final double SEARCH_RADIUS_METERS = 1500;
    // AlternativeFinderService와 동일한 이유로 곱셈 부스트 + 바닥값을 쓴다
    // (2026-09-14 실측 — 덧셈 부스트는 인기 랜드마크 baseScore 앞에서 무의미했음).
    private static final double PERSONALIZATION_BOOST_WEIGHT = 0.6;
    private static final double MIN_BASE_SCORE_FOR_BOOST = 1.0;

    public record Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations) {
    }

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final ApiCallLogService apiCallLogService;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final PersonalizationService personalizationService;

    public GapRecommendationService(
            TripRepository tripRepository, ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository, GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService, ApiCallLogService apiCallLogService,
            PlaceEmbeddingService placeEmbeddingService, PersonalizationService personalizationService
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.apiCallLogService = apiCallLogService;
        this.placeEmbeddingService = placeEmbeddingService;
        this.personalizationService = personalizationService;
    }

    public Optional<List<Gap>> findGaps(User user, Long tripId, int day) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(itinerary -> computeGaps(itinerary, user));
    }

    private List<Gap> computeGaps(Itinerary itinerary, User user) {
        List<TripPlace> places = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
        List<Gap> gaps = new ArrayList<>();

        for (int i = 0; i < places.size() - 1; i++) {
            TripPlace before = places.get(i);
            TripPlace after = places.get(i + 1);
            LocalTime endTime = before.getVisitEndTime();
            LocalTime startTime = after.getVisitStartTime();
            if (endTime == null || startTime == null) {
                continue;
            }
            Duration gap = Duration.between(endTime, startTime);
            if (gap.compareTo(GAP_THRESHOLD) <= 0) {
                continue;
            }
            if (before.getLatitude() == null || before.getLongitude() == null
                    || after.getLatitude() == null || after.getLongitude() == null) {
                continue;
            }
            double midLat = (before.getLatitude() + after.getLatitude()) / 2;
            double midLng = (before.getLongitude() + after.getLongitude()) / 2;
            gaps.add(new Gap(before.getId(), after.getId(), (int) gap.toMinutes(),
                    recommend(user, midLat, midLng, endTime, startTime, List.of(before, after))));
        }

        // 마지막 장소 뒤 ~ 하루 끝(21:00)도 빈 시간이다 — 장소 사이만 봐서 09:00~10:00 한 곳뿐인 날에
        // "채울 빈 시간이 없어요"가 떴다(안드로이드 사용성 테스트 2026-11-12, #414). 마지막 장소 뒤에 넣으면 되므로
        // 기존 "뒤에 넣기"(insertPlaceAfter)를 그대로 쓴다.
        if (!places.isEmpty()) {
            TripPlace last = places.get(places.size() - 1);
            LocalTime endTime = last.getVisitEndTime();
            if (endTime != null && endTime.isBefore(DAY_END) && last.getLatitude() != null && last.getLongitude() != null) {
                Duration gap = Duration.between(endTime, DAY_END);
                if (gap.compareTo(GAP_THRESHOLD) > 0) {
                    gaps.add(new Gap(last.getId(), null, (int) gap.toMinutes(),
                            recommend(user, last.getLatitude(), last.getLongitude(), endTime, DAY_END, List.of(last))));
                }
            }
        }
        return gaps;
    }

    private List<AlternativeCandidate> recommend(User user, double lat, double lng, LocalTime from, LocalTime to,
                                                 List<TripPlace> neighbors) {
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
        placeEmbeddingService.ensureEmbeddings(candidates);

        // 앞뒤 장소 자기 자신이 "빈 시간 추천"으로 다시 튀어나오면 안 된다 — 원래 있던 장소를 다시 넣는 결과가 된다.
        // 숙소·역·학교는 빈 시간에 들를 곳이 아니라 뺀다(#414).
        List<Place> filteredCandidates = candidates.stream()
                .filter(c -> neighbors.stream().noneMatch(n -> isSameGooglePlace(c, n)))
                .filter(c -> !NOT_A_STOP.contains(c.getCategory()))
                .toList();
        // Comparator 안에서 점수를 계산하면 정렬 비교마다(O(n log n)회) 매번 다시
        // 계산돼 personalizationScore의 pgvector 조회가 그만큼 반복된다 — 후보당
        // 한 번만 계산해 맵에 담아두고 정렬은 조회 없이 맵 조회만 하도록 한다.
        Map<Long, Double> scoreByPlaceId = new HashMap<>();
        for (Place c : filteredCandidates) {
            double score = Math.max(PlaceScoring.baseScore(c), MIN_BASE_SCORE_FOR_BOOST)
                    * (1 + personalizationService.personalizationScore(user, c) * PERSONALIZATION_BOOST_WEIGHT);
            scoreByPlaceId.put(c.getId(), score);
        }
        return filteredCandidates.stream()
                .sorted(Comparator.comparingDouble((Place c) -> scoreByPlaceId.get(c.getId())).reversed())
                .map(c -> new AlternativeCandidate(
                        c.getId(), c.getGooglePlaceId(), c.getName(), c.getCategory(), c.getRating(),
                        c.getUserRatingCount(), c.getLatitude(), c.getLongitude(), c.getAddress(),
                        null, null, false, null, null))
                .toList();
    }

    private static boolean overlaps(LocalTime from, LocalTime to, LocalTime windowFrom, LocalTime windowTo) {
        return from.isBefore(windowTo) && to.isAfter(windowFrom);
    }

    private boolean isSameGooglePlace(Place candidate, TripPlace tripPlace) {
        return tripPlace.getGooglePlaceId() != null
                && tripPlace.getGooglePlaceId().equals(candidate.getGooglePlaceId());
    }
}
