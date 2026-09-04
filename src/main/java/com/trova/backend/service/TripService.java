package com.trova.backend.service;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 영상 파이프라인에서 나온 SavedPlace를 Trip/Itinerary/TripPlace로 확정한다.
 * day가 배정 안 된(dayNumber == null) SavedPlace는 아직 일정 위치가 없는 상태라
 * 제외한다 — Trip 자체는 만들어지지만 그 장소는 포함되지 않는다.
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;

    public TripService(
            TripRepository tripRepository,
            ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository
    ) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
    }

    public Trip confirmVideoPlacesIntoTrip(User user, String title, List<SavedPlace> places) {
        Trip trip = tripRepository.save(new Trip(user, title, null, null));

        Map<Integer, List<SavedPlace>> byDay = places.stream()
                .filter(place -> place.getDayNumber() != null)
                .collect(Collectors.groupingBy(SavedPlace::getDayNumber, TreeMap::new, Collectors.toList()));

        for (Map.Entry<Integer, List<SavedPlace>> entry : byDay.entrySet()) {
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, entry.getKey(), null));

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
