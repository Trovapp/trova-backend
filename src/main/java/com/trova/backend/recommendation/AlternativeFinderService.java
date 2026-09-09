package com.trova.backend.recommendation;

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
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public AlternativeFinderService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner,
            SeoulCongestionApiClient seoulCongestionApiClient,
            ApiCallLogService apiCallLogService,
            PlaceEmbeddingService placeEmbeddingService
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
        this.seoulCongestionApiClient = seoulCongestionApiClient;
        this.apiCallLogService = apiCallLogService;
        this.placeEmbeddingService = placeEmbeddingService;
    }

    public Optional<List<AlternativeCandidate>> findAlternatives(User user, Long tripPlaceId, AlternativeFilter filter) {
        Optional<TripPlace> maybeTarget = tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> isOwner(p, user));
        if (maybeTarget.isEmpty()) {
            return Optional.empty();
        }
        TripPlace target = maybeTarget.get();
        if (target.getLatitude() == null || target.getLongitude() == null) {
            return Optional.of(List.of());
        }

        String includedType = filter.category() != null
                ? GoogleTypeMapper.toGoogleType(filter.category()).orElse(null)
                : null;

        // next는 검색 성공 여부와 무관하게 항상 구해둔다(검색이 실패해도 형제 조회는
        // 이미 끝나 있어야 이후 로직이 next 유무를 일관되게 판단할 수 있다).
        TripPlace next = findNext(target);

        // 검색 실패(재시도 3회 소진 후 예외)를 500으로 흘려보내지 않는다 — 대안
        // 찾기는 부가 기능이라 빈 결과로 조용히 낮춘다(스펙 "에러 처리" 절 참고).
        long searchStart = System.currentTimeMillis();
        GooglePlacesNearbySearchResponse response;
        try {
            response = googlePlacesApiClient.searchNearby(
                    target.getLatitude(), target.getLongitude(), SEARCH_RADIUS_METERS, includedType);
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - searchStart, true, null, null, null, null);
        } catch (Exception e) {
            apiCallLogService.record(
                    "google-places", "nearby-search-alternative", null,
                    System.currentTimeMillis() - searchStart, false, e.getMessage(), null, null, null);
            return Optional.of(List.of());
        }
        List<GooglePlacesNearbySearchResponse.Place> raw =
                response.places() != null ? response.places() : List.of();
        if (raw.isEmpty()) {
            return Optional.of(List.of());
        }

        List<Place> candidates = placeCatalogService.upsertAll(raw);

        // 교체 대상 자기 자신이 후보로 딸려오면 안 된다 — 선택 시 applyReplacement가
        // 아무 변화 없이 memo만 지우는 무의미한 "교체"가 되어버린다.
        if (target.getGooglePlaceId() != null) {
            candidates = candidates.stream()
                    .filter(p -> !target.getGooglePlaceId().equals(p.getGooglePlaceId()))
                    .toList();
        }

        if (Boolean.TRUE.equals(filter.indoorOnly())) {
            candidates = filterIndoor(candidates);
        }

        // includedType으로 못 걸렀으면(매핑 없던 카테고리) 이름/카테고리 텍스트로 후처리 필터링.
        if (includedType == null && filter.category() != null && !filter.category().isBlank()) {
            String needle = filter.category().trim();
            candidates = candidates.stream()
                    .filter(p -> containsIgnoreCase(p.getName(), needle) || containsIgnoreCase(p.getCategory(), needle))
                    .toList();
        }

        placeEmbeddingService.ensureEmbeddings(candidates);

        List<AlternativeCandidate> result = new ArrayList<>();
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

            if (filter.maxDistanceKm() != null && distanceToNextKm != null && distanceToNextKm > filter.maxDistanceKm()) {
                continue;
            }
            if (filter.maxTravelMinutes() != null && estimatedTravelMinutes != null
                    && estimatedTravelMinutes > filter.maxTravelMinutes()) {
                continue;
            }

            boolean congestionAvailable = false;
            String congestionLevel = null;
            if (SeoulCongestionAreaCache.isKnownArea(candidate.getName())) {
                Optional<SeoulCongestionResponse> congestion = seoulCongestionApiClient.fetchCongestion(candidate.getName());
                if (congestion.isPresent() && congestion.get().cityData() != null
                        && congestion.get().cityData().livePopulation() != null
                        && !congestion.get().cityData().livePopulation().isEmpty()) {
                    congestionAvailable = true;
                    congestionLevel = congestion.get().cityData().livePopulation().get(0).areaCongestLevel();
                }
            }

            result.add(new AlternativeCandidate(
                    candidate.getId(), candidate.getGooglePlaceId(), candidate.getName(), candidate.getCategory(),
                    candidate.getRating(), candidate.getUserRatingCount(), candidate.getLatitude(), candidate.getLongitude(),
                    candidate.getAddress(), distanceToNextKm, estimatedTravelMinutes, congestionAvailable, congestionLevel));
        }
        return Optional.of(result);
    }

    private boolean isOwner(TripPlace place, User user) {
        return place.getItinerary().getTrip().getUser().getId().equals(user.getId());
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
