package com.trova.backend.recommendation;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 시간이 입력된 연속 장소 사이에 30분 넘는 공백이 있으면, 두 장소의 중간지점
 * 기준으로 갈 만한 곳을 추천한다. 시간이 하나라도 비어있는 쌍은 건너뛴다 —
 * 임의로 시간을 추정하지 않는다.
 */
@Service
public class GapRecommendationService {

    private static final Duration GAP_THRESHOLD = Duration.ofMinutes(30);
    private static final double SEARCH_RADIUS_METERS = 1500;

    public record Gap(Long beforePlaceId, Long afterPlaceId, int gapMinutes, List<AlternativeCandidate> recommendations) {
    }

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final GooglePlacesApiClient googlePlacesApiClient;
    private final PlaceCatalogService placeCatalogService;

    public GapRecommendationService(
            TripRepository tripRepository, ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository, GooglePlacesApiClient googlePlacesApiClient,
            PlaceCatalogService placeCatalogService
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.googlePlacesApiClient = googlePlacesApiClient;
        this.placeCatalogService = placeCatalogService;
    }

    public Optional<List<Gap>> findGaps(User user, Long tripId, int day) {
        return tripRepository.findById(tripId)
                // Objects.equals로 null-safe하게 비교 — User.id는 영속화 전(단위 테스트 등)에는
                // null일 수 있다(AlternativeFinderService.isOwner와 동일한 id 비교 기준).
                .filter(trip -> Objects.equals(trip.getUser().getId(), user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(this::computeGaps);
    }

    private List<Gap> computeGaps(Itinerary itinerary) {
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
            // 검색 실패를 500으로 흘려보내지 않는다 — AlternativeFinderService와 같은 원칙.
            GooglePlacesNearbySearchResponse response;
            try {
                response = googlePlacesApiClient.searchNearby(midLat, midLng, SEARCH_RADIUS_METERS, null);
            } catch (Exception e) {
                response = new GooglePlacesNearbySearchResponse(List.of());
            }
            List<GooglePlacesNearbySearchResponse.Place> raw =
                    response.places() != null ? response.places() : List.of();
            List<Place> candidates = raw.isEmpty() ? List.of() : placeCatalogService.upsertAll(raw);

            List<AlternativeCandidate> recommendations = candidates.stream()
                    .map(c -> new AlternativeCandidate(
                            c.getId(), c.getGooglePlaceId(), c.getName(), c.getCategory(), c.getRating(),
                            c.getUserRatingCount(), c.getLatitude(), c.getLongitude(), c.getAddress(),
                            null, null, false, null))
                    .toList();

            gaps.add(new Gap(before.getId(), after.getId(), (int) gap.toMinutes(), recommendations));
        }
        return gaps;
    }
}
