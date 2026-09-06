package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.NotificationRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 영상 파이프라인에서 나온 SavedPlace를 Trip/Itinerary/TripPlace로 확정한다.
 * day가 배정 안 된(dayNumber == null) SavedPlace는 아직 일정 위치가 없는 상태라
 * 제외한다 — Trip 자체는 만들어지지만 그 장소는 포함되지 않는다.
 *
 * 사용자가 직접 만드는 Trip(장소 검색해서 추가하는 방식)도 이 클래스가 담당한다.
 */
@Service
public class TripService {

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final PlaceRepository placeRepository;
    private final NotificationRepository notificationRepository;

    public TripService(
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository,
            PlaceRepository placeRepository,
            NotificationRepository notificationRepository
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.placeRepository = placeRepository;
        this.notificationRepository = notificationRepository;
    }

    /** Trip과 그에 딸린 Itinerary/TripPlace/Notification을 전부 지운다(소유자 확인 후). */
    public boolean deleteTrip(User user, Long tripId) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    List<Itinerary> itineraries = itineraryRepository.findByTripOrderByDay(trip);
                    for (Itinerary itinerary : itineraries) {
                        notificationRepository.findByItinerary(itinerary).ifPresent(notificationRepository::delete);
                        tripPlaceRepository.deleteAll(tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary));
                    }
                    itineraryRepository.deleteAll(itineraries);
                    tripRepository.delete(trip);
                    return true;
                })
                .orElse(false);
    }

    /** 사용자가 직접 새 Trip을 만든다 — 기간(startDate~endDate) 기준으로 일차를 자동 생성한다. */
    public Trip createTrip(User user, String title, LocalDate startDate, LocalDate endDate) {
        Trip trip = tripRepository.save(new Trip(user, title, startDate, endDate));
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        for (int day = 1; day <= totalDays; day++) {
            itineraryRepository.save(new Itinerary(trip, day, startDate.plusDays(day - 1)));
        }
        return trip;
    }

    /**
     * Place 카탈로그(구글 플레이스 검색에서 이미 upsert된 장소)에서 googlePlaceId로 찾아
     * 해당 일차 맨 끝에 추가한다. 카탈로그에 없으면(검색 단계를 안 거친 잘못된 요청)
     * 아무것도 만들지 않는다.
     */
    public Optional<TripPlace> addPlaceToDay(User user, Long tripId, int day, String googlePlaceId) {
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .flatMap(itinerary -> placeRepository.findByGooglePlaceId(googlePlaceId).map(place -> {
                    List<TripPlace> siblings = tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary);
                    int nextOrder = siblings.size() + 1;
                    TripPlace tripPlace = new TripPlace(
                            itinerary, place.getName(), null, place.getCategory(),
                            place.getLatitude(), place.getLongitude(), null, place.getAddress(),
                            nextOrder, PlaceSource.NORMAL, null);
                    tripPlace.applyGooglePlaceId(place.getGooglePlaceId());
                    return tripPlaceRepository.save(tripPlace);
                }));
    }

    /** 보낸 필드만 부분적으로 갱신한다(null인 필드는 기존 값 유지 — TripPlace.applyDetails 참고). */
    public Optional<TripPlace> updateDetails(
            User user, Long tripPlaceId, LocalTime visitStartTime, LocalTime visitEndTime,
            TransportMode arrivalTransportMode, String memo
    ) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(place -> {
                    place.applyDetails(visitStartTime, visitEndTime, arrivalTransportMode, memo);
                    return tripPlaceRepository.save(place);
                });
    }

    public boolean removePlace(User user, Long tripPlaceId) {
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(p -> {
                    tripPlaceRepository.delete(p);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public Optional<TripPlace> reorderPlace(User user, Long tripPlaceId, String direction) {
        if (!VALID_DIRECTIONS.contains(direction)) {
            return Optional.empty();
        }
        return tripPlaceRepository.findById(tripPlaceId)
                .filter(p -> p.getItinerary().getTrip().getUser().getId().equals(user.getId()))
                .map(place -> {
                    List<TripPlace> siblings =
                            tripPlaceRepository.findByItineraryOrderByVisitOrder(place.getItinerary());

                    int index = -1;
                    for (int i = 0; i < siblings.size(); i++) {
                        if (siblings.get(i).getId().equals(place.getId())) {
                            index = i;
                            break;
                        }
                    }
                    int swapIndex = "UP".equals(direction) ? index - 1 : index + 1;
                    if (index < 0 || swapIndex < 0 || swapIndex >= siblings.size()) {
                        return place; // 경계값 — no-op
                    }

                    TripPlace neighbor = siblings.get(swapIndex);
                    swapVisitOrder(place, neighbor);
                    return place;
                });
    }

    private void swapVisitOrder(TripPlace a, TripPlace b) {
        int aOrder = a.getVisitOrder();
        int bOrder = b.getVisitOrder();
        a.applyVisitOrder(bOrder);
        b.applyVisitOrder(aOrder);
        tripPlaceRepository.save(a);
        tripPlaceRepository.save(b);
    }

    /**
     * startDate가 있으면 각 Itinerary에 실제 날짜(startDate + (day-1))를 계산해서
     * 넣는다 — 날씨 자동복구가 이 날짜를 기준으로 예보를 조회한다. startDate가 없으면
     * (날짜 모르는 여행) 지금까지처럼 date는 null로 남는다.
     */
    public Trip confirmVideoPlacesIntoTrip(User user, String title, List<SavedPlace> places, LocalDate startDate) {
        Map<Integer, List<SavedPlace>> byDay = places.stream()
                .filter(place -> place.getDayNumber() != null)
                .collect(Collectors.groupingBy(SavedPlace::getDayNumber, TreeMap::new, Collectors.toList()));

        LocalDate endDate = startDate != null && !byDay.isEmpty()
                ? startDate.plusDays(byDay.keySet().stream().mapToInt(Integer::intValue).max().orElse(1) - 1)
                : null;
        Trip trip = tripRepository.save(new Trip(user, title, startDate, endDate));

        for (Map.Entry<Integer, List<SavedPlace>> entry : byDay.entrySet()) {
            LocalDate itineraryDate = startDate != null ? startDate.plusDays(entry.getKey() - 1) : null;
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, entry.getKey(), itineraryDate));

            List<SavedPlace> dayPlaces = entry.getValue().stream()
                    .sorted(Comparator.comparing(
                            SavedPlace::getOrderInDay, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (SavedPlace place : dayPlaces) {
                tripPlaceRepository.save(new TripPlace(
                        itinerary, place.getPlaceName(), place.getRegion(), place.getCategory(),
                        place.getLatitude(), place.getLongitude(), place.getPhone(), place.getAddress(),
                        place.getOrderInDay() != null ? place.getOrderInDay() : 0,
                        PlaceSource.VIDEO, place.getId()));
            }
        }

        return trip;
    }
}
