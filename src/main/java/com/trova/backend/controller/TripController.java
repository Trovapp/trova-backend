package com.trova.backend.controller;

import com.trova.backend.entity.*;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.WeatherRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
public class TripController {

    private final CurrentUserService currentUserService;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final TripService tripService;
    private final ItineraryRepository itineraryRepository;
    private final com.trova.backend.repository.TripRepository tripRepository;
    private final WeatherRecoveryService weatherRecoveryService;

    public TripController(
            CurrentUserService currentUserService,
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository,
            TripService tripService,
            ItineraryRepository itineraryRepository,
            com.trova.backend.repository.TripRepository tripRepository,
            WeatherRecoveryService weatherRecoveryService
    ) {
        this.currentUserService = currentUserService;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripService = tripService;
        this.itineraryRepository = itineraryRepository;
        this.tripRepository = tripRepository;
        this.weatherRecoveryService = weatherRecoveryService;
    }

    public record ConfirmTripRequest(String title, LocalDate startDate) {
    }

    public record TripResponse(Long id, String title, LocalDate startDate, LocalDate endDate) {
        static TripResponse from(Trip trip) {
            return new TripResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate());
        }
    }

    public record WeatherCheckResponse(boolean notified, String message) {
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
