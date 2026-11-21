package com.trova.backend.recommendation;

import com.trova.backend.service.TripAccess;
import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.congestion.SeoulCongestionAreaCache;
import com.trova.backend.congestion.SeoulCongestionResponse;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일정 장소 하나를 기준으로 필터(카테고리/실내외/거리/이동시간)에 맞는 대안 후보를
 * 구글 Places 근처 검색으로 찾는다. 후보명이 서울 혼잡도 API가 커버하는 장소
 * 목록(SeoulCongestionAreaCache)에 있을 때만 SeoulCongestionApiClient를 호출해
 * 혼잡도 배지를 채운다 — 그 외에는 항상 false/null로 둔다.
 */
@Service
public class AlternativeFinderService {

    private static final Logger log = LoggerFactory.getLogger(AlternativeFinderService.class);

    private static final double SEARCH_RADIUS_METERS = 2000;
    // 이름 끝이 같은 후보를 같은 장소로 볼 거리 — 구글·카카오 좌표 차이를 넉넉히 본다(#177).
    private static final double SAME_PLACE_KM = 0.3;
    // 2026-09-14 실측: 덧셈 부스트(+최대 0.5)는 baseScore가 인기 랜드마크에서
    // 40~50대까지 나오는 걸 보니 사실상 무시할 수준이었다(경복궁 baseScore
    // ≈4.6*ln(46809)≈49.5 vs 개인화 최대 +0.5). baseScore에 비례한 곱셈 부스트로
    // 바꿔서, 인기도 차이가 큰 후보 간에는 여전히 인기도가 우세하되 비슷한
    // 인기도 안에서는 유사도가 순위를 실제로 바꿀 수 있게 한다.
    private static final double PERSONALIZATION_BOOST_WEIGHT = 0.6;
    // 곱셈 부스트라 baseScore가 0이면(평점/리뷰수 없는 신생 장소) 개인화 유사도가
    // 아무리 높아도 최종 점수가 0으로 죽는다 — 개인화가 제일 도움될 만한 "리뷰
    // 적은 숨은 장소" 케이스를 정확히 못 살리게 되므로 최소 바닥값을 둔다.
    private static final double MIN_BASE_SCORE_FOR_BOOST = 1.0;
    private static final int EXPLANATION_TOP_N = 2;

    // 대안만 복구하는 플래그. 채팅 등 공용 Gemini 생성기는 바꾸지 않는다.
    @Value("${app.alternatives.gemini-explanation-enabled:false}")
    private boolean geminiExplanationEnabled;

    // 실측 아닌 통상적 평균 속도 추정치 — 실제 도로망을 반영하는 경로 API가 아니다.
    private static final Map<TransportMode, Double> AVERAGE_SPEED_KMH = Map.of(
            TransportMode.WALK, 4.0,
            TransportMode.TRANSIT, 20.0,
            TransportMode.CAR, 30.0
    );

    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceTaggingRunner placeTaggingRunner;
    private final SeoulCongestionApiClient seoulCongestionApiClient;
    private final ApiCallLogService apiCallLogService;
    private final PlaceEmbeddingService placeEmbeddingService;
    private final PersonalizationService personalizationService;

    public AlternativeFinderService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner,
            SeoulCongestionApiClient seoulCongestionApiClient,
            ApiCallLogService apiCallLogService,
            PlaceEmbeddingService placeEmbeddingService,
            PersonalizationService personalizationService
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.seoulCongestionApiClient = seoulCongestionApiClient;
        this.apiCallLogService = apiCallLogService;
        this.placeEmbeddingService = placeEmbeddingService;
        this.personalizationService = personalizationService;
    }

    private static final LocalTime MORNING_UNTIL = LocalTime.of(10, 30);

    static boolean isMorningFriendly(String category) {
        return GoogleTypeMapper.isMorningFriendly(category);
    }

    public Optional<List<AlternativeCandidate>> findAlternatives(User user, Long tripPlaceId, AlternativeFilter filter) {
        return findAlternatives(user, tripPlaceId, filter, true);
    }

    public Optional<List<AlternativeCandidate>> findAlternatives(
            User user, Long tripPlaceId, AlternativeFilter filter, boolean allowSupplement) {
        Optional<TripPlace> maybeTarget = tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> isOwner(p, user));
        if (maybeTarget.isEmpty()) {
            return Optional.empty();
        }
        TripPlace target = maybeTarget.get();
        if (target.getLatitude() == null || target.getLongitude() == null) {
            return Optional.of(List.of());
        }

        // 필터가 없으면 원본과 같은 종류를 먼저 찾는다(QA #152, #447).
        String includedType = filter.category() != null
                ? GoogleTypeMapper.toGoogleType(filter.category()).orElse(null)
                : GoogleTypeMapper.defaultTypeFor(target.getCategory()).orElse(null);

        // next는 검색 성공 여부와 무관하게 항상 구해둔다(검색이 실패해도 형제 조회는
        // 이미 끝나 있어야 이후 로직이 next 유무를 일관되게 판단할 수 있다).
        TripPlace next = findNext(target);

        // 명시 필터 없이 같은 종류 검색에 성공했고 원본을 뺀 후보가 3곳 미만이면 한 번만 보충한다.
        // 검색 실패와 성공한 빈 결과를 구분하고, 보충 실패가 이미 확보한 후보를 지우지 않도록 한다.
        long searchStart = System.currentTimeMillis();
        Optional<List<GooglePlacesNearbySearchResponse.Place>> primarySearch = searchNearby(target, includedType);
        List<GooglePlacesNearbySearchResponse.Place> primary = primarySearch.orElse(List.of());
        Set<String> primaryIds = primary.stream()
                .map(GooglePlacesNearbySearchResponse.Place::id).collect(Collectors.toSet());
        Map<String, GooglePlacesNearbySearchResponse.Place> rawById = new LinkedHashMap<>();
        primary.forEach(p -> rawById.putIfAbsent(p.id(), p));
        long primaryCount = primaryIds.stream()
                .filter(id -> !Objects.equals(id, target.getGooglePlaceId()))
                .count();
        boolean supplemented = allowSupplement && filter.category() == null && includedType != null
                && primarySearch.isPresent() && primaryCount < 3;
        if (supplemented) {
            searchNearby(target, null).orElse(List.of()).forEach(p -> rawById.putIfAbsent(p.id(), p));
        }
        long searchMs = System.currentTimeMillis() - searchStart;
        List<GooglePlacesNearbySearchResponse.Place> raw = new ArrayList<>(rawById.values());
        if (raw.isEmpty()) {
            return Optional.of(List.of());
        }

        long upsertStart = System.currentTimeMillis();
        List<Place> candidates = placeCatalogService.upsertAll(raw);
        long upsertMs = System.currentTimeMillis() - upsertStart;

        // 교체 대상 자기 자신이 후보로 딸려오면 안 된다 — 선택 시 applyReplacement가
        // 아무 변화 없이 memo만 지우는 무의미한 "교체"가 되어버린다.
        if (target.getGooglePlaceId() != null) {
            candidates = candidates.stream()
                    .filter(p -> !target.getGooglePlaceId().equals(p.getGooglePlaceId()))
                    .toList();
        }

        // 같은 여행에 이미 있는 장소가 대안으로 나오면 안 된다 — 진관사계곡 대안으로 같은 여행의 은평한옥마을이 나왔다(#177).
        // 영상에서 온 장소는 구글 장소 ID가 없어 이름(공백 무시)으로도 거른다. 같은 주소의 다른 가게가 있어 거리로는 거르지 않는다.
        List<TripPlace> inTrip = tripPlaceRepository.findByItinerary_Trip(target.getItinerary().getTrip());
        Set<String> tripGoogleIds = inTrip.stream().map(TripPlace::getGooglePlaceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> tripNames = inTrip.stream().map(p -> normalizeName(p.getPlaceName()))
                .filter(n -> !n.isEmpty()).collect(Collectors.toSet());
        candidates = candidates.stream()
                .filter(p -> p.getGooglePlaceId() == null || !tripGoogleIds.contains(p.getGooglePlaceId()))
                .filter(p -> !tripNames.contains(normalizeName(p.getName())))
                .filter(p -> inTrip.stream().noneMatch(t -> sameNamedNearby(p, t)))
                .toList();

        // 필터 없이 찾을 때 숙소·역·학교는 대신 갈 곳이 아니라 뺀다(빈 시간 추천과 같은 규칙, #416).
        // 바꾸려는 곳이 숙소·역이면 같은 종류가 대안이므로 그대로 둔다.
        boolean lodgingTarget = GoogleTypeMapper.defaultTypeFor(target.getCategory()).filter("lodging"::equals).isPresent();
        if (filter.category() == null && !lodgingTarget
                && (target.getCategory() == null || !GapRecommendationService.NOT_A_STOP.contains(target.getCategory()))) {
            candidates = candidates.stream()
                    .filter(p -> !(p.getCategory() != null && GapRecommendationService.NOT_A_STOP.contains(p.getCategory())))
                    .toList();
        }

        long tagStart = System.currentTimeMillis();
        if (Boolean.TRUE.equals(filter.indoorOnly())) {
            candidates = filterIndoor(candidates);
        }
        long tagMs = System.currentTimeMillis() - tagStart;

        // includedType으로 못 걸렀으면(매핑 없던 카테고리) 이름/카테고리 텍스트로 후처리 필터링.
        if (includedType == null && filter.category() != null && !filter.category().isBlank()) {
            String needle = filter.category().trim();
            candidates = candidates.stream()
                    .filter(p -> containsIgnoreCase(p.getName(), needle) || containsIgnoreCase(p.getCategory(), needle))
                    .toList();
        }

        // 아침 자리(10:30 전)를 바꾸면 고깃집이 09:00에 들어갔다(안드로이드 사용성 테스트 2026-11-12, #414) —
        // 음식점·전체 또는 보충 검색을 했을 때 아침이면 카페·빵집·브런치처럼 아침에 갈 만한 곳만 남긴다. 하나도 없으면 그대로 둔다.
        if (target.getVisitStartTime() != null && target.getVisitStartTime().isBefore(MORNING_UNTIL)
                && (includedType == null || "restaurant".equals(includedType) || supplemented)) {
            List<Place> morning = candidates.stream().filter(p -> isMorningFriendly(p.getCategory())).toList();
            if (!morning.isEmpty()) {
                candidates = morning;
            }
        }

        long embeddingStart = System.currentTimeMillis();
        placeEmbeddingService.ensureEmbeddings(candidates);
        long embeddingMs = System.currentTimeMillis() - embeddingStart;
        long congestionMs = 0;
        long personalizationStart = System.currentTimeMillis();
        Map<Long, Double> personalizationScores = geminiExplanationEnabled ? Map.of()
                : personalizationService.personalizationScores(user.getId(), candidates);
        long personalizationMs = System.currentTimeMillis() - personalizationStart;

        List<AlternativeCandidate> result = new ArrayList<>();
        Map<Long, Double> scoreByPlaceId = new java.util.HashMap<>();
        Map<Long, List<UserPreferenceSignalRepository.SimilarSignal>> similarSignalsByPlaceId = new java.util.HashMap<>();
        for (Place candidate : candidates) {
            // Place.latitude/longitude는 nullable(Google 응답에 location이 없을 수
            // 있음)이라, upsertAll로 캐시된 행이 좌표 없이 저장돼 있을 수 있다. target/next는
            // 이미 위에서 null 가드가 있으니, candidate 쪽도 haversineKm에 넘기기 전에
            // 걸러야 한다 — 안 그러면 캐시에 한 번 박힌 좌표 없는 행이 이후 모든 요청을
            // 영구히 500으로 만든다.
            if (candidate.getLatitude() == null || candidate.getLongitude() == null) {
                continue;
            }

            Double distanceToNextKm = null;
            Integer estimatedTravelMinutes = null;
            if (next != null && next.getLatitude() != null && next.getLongitude() != null) {
                distanceToNextKm = haversineKm(
                        candidate.getLatitude(), candidate.getLongitude(), next.getLatitude(), next.getLongitude());
                if (filter.transportMode() != null) {
                    double speedKmh = AVERAGE_SPEED_KMH.get(filter.transportMode());
                    estimatedTravelMinutes = (int) Math.round(distanceToNextKm / speedKmh * 60);
                }
            }

            // 다음 장소가 없으면(그날 마지막·유일한 장소) 거리 조건이 통째로 무시돼 "1km"를 골라도 2km 결과가 나왔다(#414) —
            // 그때는 바꿀 장소에서 잰다.
            double filterKm = distanceToNextKm != null ? distanceToNextKm
                    : haversineKm(candidate.getLatitude(), candidate.getLongitude(), target.getLatitude(), target.getLongitude());
            if (filter.maxDistanceKm() != null && filterKm > filter.maxDistanceKm()) {
                continue;
            }
            if (filter.maxTravelMinutes() != null && estimatedTravelMinutes != null
                    && estimatedTravelMinutes > filter.maxTravelMinutes()) {
                continue;
            }

            boolean congestionAvailable = false;
            String congestionLevel = null;
            long congestionStart = System.currentTimeMillis();
            if (SeoulCongestionAreaCache.isKnownArea(candidate.getName())) {
                Optional<SeoulCongestionResponse> congestion = seoulCongestionApiClient.fetchCongestion(candidate.getName());
                if (congestion.isPresent() && congestion.get().cityData() != null
                        && congestion.get().cityData().livePopulation() != null
                        && !congestion.get().cityData().livePopulation().isEmpty()) {
                    congestionAvailable = true;
                    congestionLevel = congestion.get().cityData().livePopulation().get(0).areaCongestLevel();
                }
            }

            congestionMs += System.currentTimeMillis() - congestionStart;

            long candidatePersonalizationStart = System.currentTimeMillis();
            PersonalizationService.PersonalizationResult personalization = geminiExplanationEnabled
                    ? personalizationService.retrieveAndScore(user, candidate)
                    : new PersonalizationService.PersonalizationResult(
                            personalizationScores.getOrDefault(candidate.getId(), 0.0), List.of());
            personalizationMs += System.currentTimeMillis() - candidatePersonalizationStart;
            double score = Math.max(PlaceScoring.baseScore(candidate), MIN_BASE_SCORE_FOR_BOOST)
                    * (1 + personalization.score() * PERSONALIZATION_BOOST_WEIGHT);
            scoreByPlaceId.put(candidate.getId(), score);
            similarSignalsByPlaceId.put(candidate.getId(), personalization.similarSignals());

            result.add(new AlternativeCandidate(
                    candidate.getId(), candidate.getGooglePlaceId(), candidate.getName(), candidate.getCategory(),
                    candidate.getRating(), candidate.getUserRatingCount(), candidate.getLatitude(), candidate.getLongitude(),
                    candidate.getAddress(), distanceToNextKm, estimatedTravelMinutes, congestionAvailable, congestionLevel,
                    null));
        }

        result = result.stream()
                // 유한 후보 사이의 기본 검색 우선순위는 유지하되, 무효 기본 후보도 유효 보충 후보 뒤로 둔다(#487).
                .sorted(Comparator.comparingInt((AlternativeCandidate c) -> Double.isFinite(scoreByPlaceId.get(c.placeId())) ? 0 : 1)
                        .thenComparingInt(c -> primaryIds.contains(c.googlePlaceId()) ? 0 : 1)
                        .thenComparing(Comparator.comparingDouble(
                                (AlternativeCandidate c) -> scoreByPlaceId.get(c.placeId())).reversed()))
                .toList();

        // 기본은 상위 두 곳에 규칙 문구. 복구 모드만 기존 신호로 Gemini 이유를 생성한다.
        long explanationStart = System.currentTimeMillis();
        List<AlternativeCandidate> withReasons = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            AlternativeCandidate c = result.get(i);
            if (i < EXPLANATION_TOP_N) {
                Place candidatePlace = candidates.stream()
                        .filter(p -> java.util.Objects.equals(p.getId(), c.placeId()))
                        .findFirst()
                        .orElse(null);
                Optional<String> reason;
                if (geminiExplanationEnabled) {
                    reason = candidatePlace != null
                            ? personalizationService.explainFromSignals(candidatePlace,
                                    similarSignalsByPlaceId.getOrDefault(candidatePlace.getId(), List.of()))
                            : Optional.empty();
                } else {
                    double distanceKm = haversineKm(target.getLatitude(), target.getLongitude(), c.latitude(), c.longitude());
                    reason = Optional.ofNullable(AlternativeReason.create(distanceKm, target.getCategory(), c.category(),
                            personalizationScores.getOrDefault(c.placeId(), 0.0), c.rating(), c.userRatingCount()));
                }
                withReasons.add(new AlternativeCandidate(
                        c.placeId(), c.googlePlaceId(), c.name(), c.category(), c.rating(), c.userRatingCount(),
                        c.latitude(), c.longitude(), c.address(), c.distanceToNextKm(), c.estimatedTravelMinutes(),
                        c.isCongestionAvailable(), c.congestionLevel(), reason.orElse(null)));
            } else {
                withReasons.add(c);
            }
        }
        // 처음 보는 지역의 첫 대화가 느린 구간을 찾으려고 요청마다 구간별 시간을 한 줄로 남긴다(#77).
        // 임베딩 구간의 요청/저장 세부는 PlaceEmbeddingService가 따로 남긴다.
        log.info("대안 찾기 구간별 시간: 검색={}ms 카탈로그저장={}ms({}곳) 실내태그={}ms 임베딩={}ms 혼잡도={}ms 개인화조회={}ms({}곳) 추천이유={}ms",
                searchMs,
                upsertMs, raw.size(), tagMs, embeddingMs, congestionMs, personalizationMs, result.size(),
                System.currentTimeMillis() - explanationStart);
        return Optional.of(withReasons);
    }

    private Optional<List<GooglePlacesNearbySearchResponse.Place>> searchNearby(TripPlace target, String includedType) {
        long start = System.currentTimeMillis();
        try {
            GooglePlacesNearbySearchResponse response = googlePlacesApiClient.searchNearby(
                    target.getLatitude(), target.getLongitude(), SEARCH_RADIUS_METERS, includedType);
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - start, true, null, null, null, null);
            return Optional.of(response.places() != null ? response.places() : List.of());
        } catch (Exception e) {
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - start, false, e.getMessage(), null, null, null);
            return Optional.empty();
        }
    }

    // 구글 이름엔 종단명·상호가 앞에 붙는다("대한불교조계종 진관사") — 여행 장소 이름으로 끝나고 가까우면 같은 곳.
    // "청류 진관사점"처럼 이름이 들어 있어도 끝이 다르면 다른 가게라 남긴다.
    private boolean sameNamedNearby(Place candidate, TripPlace tripPlace) {
        String tripName = normalizeName(tripPlace.getPlaceName());
        if (tripName.length() < 2 || !normalizeName(candidate.getName()).endsWith(tripName)) {
            return false;
        }
        if (candidate.getLatitude() == null || tripPlace.getLatitude() == null) {
            return false;
        }
        return haversineKm(candidate.getLatitude(), candidate.getLongitude(),
                tripPlace.getLatitude(), tripPlace.getLongitude()) <= SAME_PLACE_KM;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isOwner(TripPlace place, User user) {
        return TripAccess.requireMember(place.getItinerary().getTrip(), user);
    }

    private TripPlace findNext(TripPlace target) {
        List<TripPlace> siblings = tripPlaceRepository.findByItineraryOrderByVisitOrder(target.getItinerary());
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(target.getId())) {
                return i + 1 < siblings.size() ? siblings.get(i + 1) : null;
            }
        }
        return null;
    }

    private List<Place> filterIndoor(List<Place> candidates) {
        List<Place> needsTagging = candidates.stream().filter(p -> p.getSpace() == null).toList();
        if (!needsTagging.isEmpty()) {
            List<PlaceTaggingRunner.TagCandidate> tagCandidates = new ArrayList<>();
            for (int i = 0; i < needsTagging.size(); i++) {
                Place p = needsTagging.get(i);
                tagCandidates.add(new PlaceTaggingRunner.TagCandidate(
                        i, p.getName(), p.getCategory(), p.getRating(), p.getUserRatingCount(), p.getPriceLevel()));
            }
            // 태깅 서브프로세스 실패(무료 티어 429, 스크립트 부재, 2분 타임아웃 등,
            // PlaceTaggingRunner.run이 던지는 PipelineException)를 500으로 흘려보내지
            // 않는다 — searchNearby 가드와 같은 원칙("대안 찾기는 실패해도 화면이
            // 죽으면 안 된다"). 실패하면 그냥 태깅 전 상태(space=null)로 두고 계속
            // 진행한다 — 이 온디맨드 태깅은 필터 보강일 뿐 검색 자체의 필수 조건이 아니다.
            try {
                List<PlaceTag> tags = placeTaggingRunner.run(tagCandidates, System.nanoTime());
                Map<Integer, PlaceTag> tagByIndex = tags.stream().collect(Collectors.toMap(PlaceTag::index, t -> t));
                for (int i = 0; i < needsTagging.size(); i++) {
                    PlaceTag tag = tagByIndex.get(i);
                    if (tag != null) {
                        needsTagging.get(i).applySpaceTag(tag.space());
                    }
                }
            } catch (Exception e) {
                log.warn("대안 찾기 실내외 온디맨드 태깅 실패 — 태깅 없이 진행합니다", e);
            }
        }
        return candidates.stream().filter(p -> "INDOOR".equals(p.getSpace())).toList();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static final double EARTH_RADIUS_KM = 6371.0;

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
