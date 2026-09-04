package com.trova.backend.controller;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.WeatherRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
public class TripController {

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");

    private final CurrentUserService currentUserService;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final TripService tripService;
    private final ItineraryRepository itineraryRepository;
    private final TripRepository tripRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final WeatherRecoveryService weatherRecoveryService;

    public TripController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            TripService tripService,
            ItineraryRepository itineraryRepository,
            TripRepository tripRepository,
            TripPlaceRepository tripPlaceRepository,
            WeatherRecoveryService weatherRecoveryService
    ) {
        this.currentUserService = currentUserService;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripService = tripService;
        this.itineraryRepository = itineraryRepository;
        this.tripRepository = tripRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.weatherRecoveryService = weatherRecoveryService;
    }

    public record ConfirmTripRequest(String title, LocalDate startDate) {
    }

    public record CreateTripRequest(String title, LocalDate startDate, LocalDate endDate) {
    }

    public record AddPlaceRequest(String query) {
    }

    public record ReorderRequest(String direction) {
    }

    public record TripResponse(Long id, String title, LocalDate startDate, LocalDate endDate) {
        static TripResponse from(Trip trip) {
            return new TripResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate());
        }
    }

    public record TripPlaceResponse(
            Long id, String placeName, String region, String category,
            Double latitude, Double longitude, String phone, String address,
            int visitOrder, String source
    ) {
        static TripPlaceResponse from(TripPlace p) {
            return new TripPlaceResponse(
                    p.getId(), p.getPlaceName(), p.getRegion(), p.getCategory(),
                    p.getLatitude(), p.getLongitude(), p.getPhone(), p.getAddress(),
                    p.getVisitOrder(), p.getSource().name());
        }
    }

    public record ItineraryResponse(Long id, int day, LocalDate date, List<TripPlaceResponse> places) {
    }

    public record TripDetailResponse(
            Long id, String title, LocalDate startDate, LocalDate endDate, List<ItineraryResponse> days
    ) {
    }

    public record WeatherCheckResponse(boolean notified, String message) {
    }

    @PostMapping("/api/trips")
    public ResponseEntity<TripResponse> createTrip(
            OAuth2AuthenticationToken authentication, @RequestBody CreateTripRequest request
    ) {
        if (request == null || request.title() == null || request.title().isBlank()
                || request.startDate() == null || request.endDate() == null
                || request.endDate().isBefore(request.startDate())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        Trip trip = tripService.createTrip(user, request.title(), request.startDate(), request.endDate());
        return ResponseEntity.ok(TripResponse.from(trip));
    }

    @GetMapping("/api/trips")
    public List<TripResponse> listTrips(OAuth2AuthenticationToken authentication) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findByUserOrderByCreatedAtDesc(user).stream().map(TripResponse::from).toList();
    }

    @GetMapping("/api/trips/{id}")
    public ResponseEntity<TripDetailResponse> getTrip(
            OAuth2AuthenticationToken authentication, @PathVariable Long id
    ) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findById(id)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .map(trip -> {
                    List<ItineraryResponse> days = itineraryRepository.findByTripOrderByDay(trip).stream()
                            .map(itinerary -> new ItineraryResponse(
                                    itinerary.getId(), itinerary.getDay(), itinerary.getDate(),
                                    tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary).stream()
                                            .map(TripPlaceResponse::from)
                                            .toList()))
                            .toList();
                    return ResponseEntity.ok(new TripDetailResponse(
                            trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(), days));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/trips/{id}")
    public ResponseEntity<Void> deleteTrip(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean deleted = tripService.deleteTrip(user, id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/trips/{tripId}/days/{day}/places")
    public ResponseEntity<TripPlaceResponse> addPlace(
            OAuth2AuthenticationToken authentication, @PathVariable Long tripId, @PathVariable int day,
            @RequestBody AddPlaceRequest request
    ) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.addPlaceToDay(user, tripId, day, request.query())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/trip-places/{id}")
    public ResponseEntity<Void> removePlace(OAuth2AuthenticationToken authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        boolean removed = tripService.removePlace(user, id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PatchMapping("/api/trip-places/{id}/order")
    public ResponseEntity<TripPlaceResponse> reorderPlace(
            OAuth2AuthenticationToken authentication, @PathVariable Long id, @RequestBody ReorderRequest request
    ) {
        if (request == null || request.direction() == null || !VALID_DIRECTIONS.contains(request.direction())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return tripService.reorderPlace(user, id, request.direction())
                .map(place -> ResponseEntity.ok(TripPlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/places/videos/{jobId}/confirm-trip")
    public ResponseEntity<TripResponse> confirmTrip(
            OAuth2AuthenticationToken authentication, @PathVariable Long jobId, @RequestBody ConfirmTripRequest request
    ) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> {
                    List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);
                    Trip trip = tripService.confirmVideoPlacesIntoTrip(user, request.title(), places, request.startDate());
                    return ResponseEntity.ok(TripResponse.from(trip));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trips/{tripId}/days/{day}/weather-check")
    public ResponseEntity<WeatherCheckResponse> weatherCheck(
            OAuth2AuthenticationToken authentication, @PathVariable Long tripId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findById(tripId)
                .filter(trip -> trip.getUser().getId().equals(user.getId()))
                .flatMap(trip -> itineraryRepository.findByTripAndDay(trip, day))
                .map(itinerary -> {
                    boolean notified = weatherRecoveryService.checkAndNotify(itinerary).isPresent();
                    String message = notified ? "비 소식이 있어 알림을 만들었어요" : "알림을 만들 조건이 아니에요(비 소식 없음/실외 장소 없음/이미 알림 있음)";
                    return ResponseEntity.ok(new WeatherCheckResponse(notified, message));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
