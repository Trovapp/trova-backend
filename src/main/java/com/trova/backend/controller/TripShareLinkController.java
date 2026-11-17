package com.trova.backend.controller;

import com.trova.backend.admin.RequestRateLimiter;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.DailyQuotaService;
import com.trova.backend.service.TripService;
import com.trova.backend.service.TripShareLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@RestController
public class TripShareLinkController {

    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final TripShareLinkService shareLinkService;
    private final ItineraryRepository itineraryRepository;
    private final TripPlaceRepository tripPlaceRepository;
    private final RequestRateLimiter rateLimiter;
    private final int publicPerMinute;
    private final TripService tripService;
    private final DailyQuotaService dailyQuotaService;

    public TripShareLinkController(
            CurrentUserService currentUserService, TripRepository tripRepository,
            TripShareLinkService shareLinkService, ItineraryRepository itineraryRepository,
            TripPlaceRepository tripPlaceRepository, RequestRateLimiter rateLimiter,
            TripService tripService, DailyQuotaService dailyQuotaService,
            @Value("${app.share-link.public-per-minute:60}") int publicPerMinute
    ) {
        this.currentUserService = currentUserService;
        this.tripRepository = tripRepository;
        this.shareLinkService = shareLinkService;
        this.itineraryRepository = itineraryRepository;
        this.tripPlaceRepository = tripPlaceRepository;
        this.rateLimiter = rateLimiter;
        this.publicPerMinute = publicPerMinute;
        this.tripService = tripService;
        this.dailyQuotaService = dailyQuotaService;
    }

    public record ShareLinkResponse(String token, String url, LocalDateTime createdAt) {
    }

    public record PublicTripResponse(String title, LocalDate startDate, LocalDate endDate, List<PublicDay> days) {
    }

    public record PublicDay(int day, LocalDate date, List<PublicPlace> places) {
    }

    public record PublicPlace(
            String placeName, String region, String category, Double latitude, Double longitude,
            String address, String phone, int visitOrder, LocalTime visitStartTime,
            LocalTime visitEndTime, String arrivalTransportMode
    ) {
    }

    @GetMapping("/api/trips/{id}/share-link")
    public ResponseEntity<ShareLinkResponse> getShareLink(Authentication authentication, @PathVariable Long id) {
        return ownedTrip(authentication, id)
                .flatMap(shareLinkService::getActive)
                .map(link -> ResponseEntity.ok(toResponse(link)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/api/trips/{id}/share-link")
    public ResponseEntity<ShareLinkResponse> createShareLink(Authentication authentication, @PathVariable Long id) {
        return ownedTrip(authentication, id)
                .map(trip -> {
                    TripShareLinkService.CreateResult result = shareLinkService.createOrGet(trip);
                    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                            .body(toResponse(result.link()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/trips/{id}/share-link")
    public ResponseEntity<Void> revokeShareLink(Authentication authentication, @PathVariable Long id) {
        boolean revoked = ownedTrip(authentication, id).map(shareLinkService::revoke).orElse(false);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/public/trips/{token}")
    public ResponseEntity<PublicTripResponse> getPublicTrip(@PathVariable String token, HttpServletRequest http) {
        if (!rateLimiter.tryAcquire("public-trip:" + http.getRemoteAddr(), publicPerMinute)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60").build();
        }
        return shareLinkService.findPublic(token)
                .map(trip -> ResponseEntity.ok(toPublicResponse(trip)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CopyTripResponse(Long tripId, String title) {
    }

    @PostMapping("/api/public/trips/{token}/copy")
    public ResponseEntity<CopyTripResponse> copyPublicTrip(
            Authentication authentication, @PathVariable String token, HttpServletRequest http
    ) {
        if (!rateLimiter.tryAcquire("public-trip:" + http.getRemoteAddr(), publicPerMinute)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "60").build();
        }
        User target = currentUserService.resolve(authentication);
        return shareLinkService.findPublic(token)
                .map(source -> {
                    dailyQuotaService.consumeTripCopy(target);
                    Trip copy;
                    try {
                        copy = tripService.copyTrip(target, source);
                    } catch (RuntimeException exception) {
                        dailyQuotaService.releaseTripCopy(target);
                        throw exception;
                    }
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new CopyTripResponse(copy.getId(), copy.getTitle()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<Trip> ownedTrip(Authentication authentication, Long id) {
        User user = currentUserService.resolve(authentication);
        return tripRepository.findById(id)
                .filter(trip -> trip.getUser().getId().equals(user.getId()));
    }

    private ShareLinkResponse toResponse(TripShareLink link) {
        return new ShareLinkResponse(link.getToken(), shareLinkService.url(link.getToken()), link.getCreatedAt());
    }

    private PublicTripResponse toPublicResponse(Trip trip) {
        List<PublicDay> days = itineraryRepository.findByTripOrderByDay(trip).stream()
                .map(itinerary -> new PublicDay(itinerary.getDay(), itinerary.getDate(),
                        tripPlaceRepository.findByItineraryOrderByVisitOrder(itinerary).stream()
                                .map(place -> new PublicPlace(
                                        place.getPlaceName(), place.getRegion(), place.getCategory(),
                                        place.getLatitude(), place.getLongitude(), place.getAddress(), place.getPhone(),
                                        place.getVisitOrder(), place.getVisitStartTime(), place.getVisitEndTime(),
                                        place.getArrivalTransportMode() != null ? place.getArrivalTransportMode().name() : null))
                                .toList()))
                .toList();
        return new PublicTripResponse(trip.getTitle(), trip.getStartDate(), trip.getEndDate(), days);
    }
}
