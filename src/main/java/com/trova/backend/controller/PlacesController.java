package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.ItineraryEditService;
import com.trova.backend.service.ItineraryGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private static final Set<String> VALID_DIRECTIONS = Set.of("UP", "DOWN");

    private final CurrentUserService currentUserService;
    private final SavedPlaceRepository savedPlaceRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final ItineraryGenerationService itineraryGenerationService;
    private final ItineraryEditService itineraryEditService;

    public PlacesController(
            CurrentUserService currentUserService,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository,
            ItineraryGenerationService itineraryGenerationService,
            ItineraryEditService itineraryEditService
    ) {
        this.currentUserService = currentUserService;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.itineraryGenerationService = itineraryGenerationService;
        this.itineraryEditService = itineraryEditService;
    }

    public record PlaceResponse(
            Long id, Long jobId, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl, String title,
            String sourcePlatform, String createdAt, Integer dayNumber, Integer orderInDay,
            String phone, String address, String roadAddress,
            String kakaoCategoryName, String kakaoPlaceUrl
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getProcessingJob().getId(), place.getPlaceName(), place.getRegion(),
                    place.getCategory(), place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getTitle(), place.getSourcePlatform().name(), place.getCreatedAt().toString(),
                    place.getDayNumber(), place.getOrderInDay(),
                    place.getPhone(), place.getAddress(), place.getRoadAddress(),
                    place.getKakaoCategoryName(), place.getKakaoPlaceUrl()
            );
        }
    }

    public record PendingJobResponse(
            Long jobId, String sourceUrl, String title, String sourcePlatform, String status, String createdAt
    ) {
        static PendingJobResponse from(ProcessingJob job) {
            return new PendingJobResponse(
                    job.getId(), job.getSourceUrl(), job.getTitle(), job.getSourcePlatform().name(),
                    job.getStatus().name(), job.getCreatedAt().toString()
            );
        }
    }

    public record MoveDayRequest(Integer dayNumber) {
    }

    public record ReorderRequest(String direction) {
    }

    @GetMapping
    public List<PlaceResponse> list(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @GetMapping("/pending")
    public List<PendingJobResponse> pending(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        // FAILED도 포함한다 — 실패한 job은 SavedPlace가 안 생겨서, 여기서 빼면
        // 사용자 입장에서 요청이 이유 없이 사라진 것처럼 보인다.
        return processingJobRepository.findByUserAndStatusIn(
                        user, List.of(JobStatus.PENDING, JobStatus.PROCESSING, JobStatus.FAILED))
                .stream()
                .map(PendingJobResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaceResponse> get(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id) {
        User user = currentUserService.resolve(authentication);
        return savedPlaceRepository.findByIdAndUser(id, user)
                .map(place -> {
                    savedPlaceRepository.delete(place);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/day")
    public ResponseEntity<PlaceResponse> moveDay(
            Authentication authentication, @PathVariable Long id, @RequestBody MoveDayRequest body
    ) {
        if (body == null || body.dayNumber() == null || body.dayNumber() < 1) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return itineraryEditService.moveToDay(id, user, body.dayNumber())
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/order")
    public ResponseEntity<PlaceResponse> reorder(
            Authentication authentication, @PathVariable Long id, @RequestBody ReorderRequest body
    ) {
        if (body == null || body.direction() == null || !VALID_DIRECTIONS.contains(body.direction())) {
            return ResponseEntity.badRequest().build();
        }
        User user = currentUserService.resolve(authentication);
        return itineraryEditService.reorder(id, user, body.direction())
                .map(place -> ResponseEntity.ok(PlaceResponse.from(place)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/videos/{jobId}/days/{day}/optimize-route")
    public ResponseEntity<List<PlaceResponse>> optimizeRoute(
            Authentication authentication, @PathVariable Long jobId, @PathVariable int day
    ) {
        User user = currentUserService.resolve(authentication);
        return itineraryEditService.optimizeRoute(jobId, user, day)
                .map(places -> ResponseEntity.ok(places.stream().map(PlaceResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/videos/{jobId}/itinerary")
    public ResponseEntity<Void> generateItinerary(Authentication authentication, @PathVariable Long jobId) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .filter(job -> job.getStatus() == JobStatus.DONE)
                .map(job -> {
                    itineraryGenerationService.generate(jobId);
                    return ResponseEntity.accepted().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
