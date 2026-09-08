package com.trova.backend.recommendation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTag;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.repository.TripPlaceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 일정 장소 하나를 기준으로 필터(카테고리/실내외/거리/이동시간)에 맞는 대안 후보를
 * 구글 Places 근처 검색으로 찾는다. 혼잡도는 별도 클라이언트(SeoulCongestionApiClient)가
 * 채워넣는다 — 이 서비스는 혼잡도 필드를 항상 false/null로 둔다.
 */
@Service
public class AlternativeFinderService {

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

    public AlternativeFinderService(
            GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService,
            TripPlaceRepository tripPlaceRepository,
            PlaceTaggingRunner placeTaggingRunner
    ) {
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeTaggingRunner = placeTaggingRunner;
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
        GooglePlacesNearbySearchResponse response;
        try {
            response = googlePlacesApiClient.searchNearby(
                    target.getLatitude(), target.getLongitude(), SEARCH_RADIUS_METERS, includedType);
        } catch (Exception e) {
            return Optional.of(List.of());
        }
        List<GooglePlacesNearbySearchResponse.Place> raw =
                response.places() != null ? response.places() : List.of();
        if (raw.isEmpty()) {
            return Optional.of(List.of());
        }

        List<Place> candidates = placeCatalogService.upsertAll(raw);

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

        List<AlternativeCandidate> result = new ArrayList<>();
        for (Place candidate : candidates) {
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

            result.add(new AlternativeCandidate(
                    candidate.getId(), candidate.getGooglePlaceId(), candidate.getName(), candidate.getCategory(),
                    candidate.getRating(), candidate.getUserRatingCount(), candidate.getLatitude(), candidate.getLongitude(),
                    candidate.getAddress(), distanceToNextKm, estimatedTravelMinutes, false, null));
        }
        return Optional.of(result);
    }

    // User.id는 영속화되지 않은 엔티티(예: 지연 로딩 프록시 초기화 전, 혹은 단위 테스트에서
    // 직접 생성한 객체)에서 null일 수 있어 id 비교 대신 (provider, providerUserId) 자연키로
    // 비교한다 — User 테이블의 유니크 제약과 동일한 기준이라 의미상으로도 더 정확하다.
    private boolean isOwner(TripPlace place, User user) {
        User owner = place.getItinerary().getTrip().getUser();
        return owner.getProvider().equals(user.getProvider())
                && owner.getProviderUserId().equals(user.getProviderUserId());
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
            List<PlaceTag> tags = placeTaggingRunner.run(tagCandidates, System.nanoTime());
            Map<Integer, PlaceTag> tagByIndex = tags.stream().collect(Collectors.toMap(PlaceTag::index, t -> t));
            for (int i = 0; i < needsTagging.size(); i++) {
                PlaceTag tag = tagByIndex.get(i);
                if (tag != null) {
                    needsTagging.get(i).applySpaceTag(tag.space());
                }
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
