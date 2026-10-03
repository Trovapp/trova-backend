package com.trova.backend.controller;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.FoundPlaceNameStore;
import com.trova.backend.service.ItineraryEditService;
import com.trova.backend.service.ItineraryGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final FoundPlaceNameStore foundPlaceNameStore;

    public PlacesController(
            CurrentUserService currentUserService,
            SavedPlaceRepository savedPlaceRepository,
            ProcessingJobRepository processingJobRepository,
            ItineraryGenerationService itineraryGenerationService,
            ItineraryEditService itineraryEditService,
            FoundPlaceNameStore foundPlaceNameStore
    ) {
        this.currentUserService = currentUserService;
        this.savedPlaceRepository = savedPlaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.itineraryGenerationService = itineraryGenerationService;
        this.itineraryEditService = itineraryEditService;
        this.foundPlaceNameStore = foundPlaceNameStore;
    }

    public record PlaceResponse(
            Long id, Long jobId, String placeName, String region, String category,
            Double latitude, Double longitude, String sourceUrl, String title,
            String sourcePlatform, String createdAt, Integer dayNumber, Integer orderInDay,
            String phone, String address, String roadAddress,
            String kakaoCategoryName, String kakaoPlaceUrl,
            // 영상에서 말한 내용(#104). 없으면 빈 배열.
            List<String> videoNotes
    ) {
        static PlaceResponse from(SavedPlace place) {
            return new PlaceResponse(
                    place.getId(), place.getProcessingJob().getId(), place.getPlaceName(), place.getRegion(),
                    place.getCategory(), place.getLatitude(), place.getLongitude(), place.getSourceUrl(),
                    place.getTitle(), place.getSourcePlatform().name(), place.getCreatedAt().toString(),
                    place.getDayNumber(), place.getOrderInDay(),
                    place.getPhone(), place.getAddress(), place.getRoadAddress(),
                    place.getKakaoCategoryName(), place.getKakaoPlaceUrl(),
                    place.getVideoNotes()
            );
        }
    }

    public record PendingJobResponse(
            Long jobId, String sourceUrl, String title, String sourcePlatform, String status, String createdAt,
            String currentStage, Integer progressPercent, String stageMessage,
            // 분석이 끝나기 전에 파이프라인이 먼저 찾은 장소 이름(#51). 아직 없으면 빈 배열.
            List<String> foundPlaceNames,
            // 실패 이유 코드. 장소를 못 찾았으면 NO_PLACES(#55), Gemini 하루 한도 소진이면 AI_QUOTA(#63),
            // 인스타그램 속도 제한이면 SOURCE_RATE_LIMITED(#65), 그 외 null.
            String failureReason
    ) {
        static PendingJobResponse from(ProcessingJob job, List<String> foundPlaceNames) {
            ProcessingStage stage = job.getCurrentStage();
            return new PendingJobResponse(
                    job.getId(), job.getSourceUrl(), job.getTitle(), job.getSourcePlatform().name(),
                    job.getStatus().name(), job.getCreatedAt().toString(),
                    stage != null ? stage.name() : null,
                    stage != null ? stage.percent() : null,
                    stage != null ? stage.message() : null,
                    foundPlaceNames,
                    job.isNoPlacesFailure() ? "NO_PLACES"
                            : job.isAiQuotaFailure() ? "AI_QUOTA"
                            : job.isSourceRateLimitFailure() ? "SOURCE_RATE_LIMITED" : null
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
        List<SavedPlace> places = savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(user);
        // 같은 영상의 결과가 여러 개면 대표 결과의 장소만 내려준다(#87, VideoResults). 데이터는 지우지 않는다.
        Map<String, Long> representative = VideoResults.latestJobIdByVideo(places);
        return places.stream()
                .filter(place -> place.getProcessingJob().getId().equals(
                        representative.get(ShareUrl.videoKey(place.getProcessingJob().getSourceUrl()))))
                .map(PlaceResponse::from)
                .toList();
    }

    @GetMapping("/pending")
    public List<PendingJobResponse> pending(Authentication authentication) {
        User user = currentUserService.resolve(authentication);
        // FAILED도 포함한다 — 실패한 job은 SavedPlace가 안 생겨서, 여기서 빼면
        // 사용자 입장에서 요청이 이유 없이 사라진 것처럼 보인다.
        List<ProcessingJob> jobs = processingJobRepository.findByUserAndStatusIn(
                user, List.of(JobStatus.PENDING, JobStatus.PROCESSING, JobStatus.FAILED));
        return hideSupersededFailures(user, jobs).stream()
                .map(job -> PendingJobResponse.from(job, foundPlaceNameStore.get(job.getId())))
                .toList();
    }

    /**
     * 실패 카드는 그 영상의 가장 최근 작업이 실패일 때만 보여준다(#83). 운영에서 9월 초 실패한 영상들이 나중에
     * 다시 분석돼 성공했는데도 실패 카드가 영상 기록 맨 위에 계속 남았고, 30초 간격으로 두 번 실패한 영상은
     * 카드가 두 장이었다. 이후 작업(성공·처리 중·더 최근 실패)이 있으면 예전 실패는 목록에서만 뺀다 — 데이터는 지우지 않는다.
     * 같은 영상인지는 정식 주소로 비교한다(#49 이전 작업은 ?si= 같은 꼬리가 붙어 저장돼 있다).
     */
    private List<ProcessingJob> hideSupersededFailures(User user, List<ProcessingJob> jobs) {
        if (jobs.stream().noneMatch(job -> job.getStatus() == JobStatus.FAILED)) {
            // 처리 목록은 분석 중 몇 초마다 폴링된다 — 실패가 없으면 전체 작업 조회를 하지 않는다.
            return jobs;
        }
        Map<String, Long> latestJobIdByVideo = new HashMap<>();
        for (ProcessingJob job : processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user)) {
            latestJobIdByVideo.merge(ShareUrl.videoKey(job.getSourceUrl()), job.getId(), Math::max);
        }
        return jobs.stream()
                .filter(job -> job.getStatus() != JobStatus.FAILED
                        || job.getId().equals(latestJobIdByVideo.get(ShareUrl.videoKey(job.getSourceUrl()))))
                .toList();
    }


    @DeleteMapping("/pending/{jobId}")
    public ResponseEntity<Void> deletePendingJob(Authentication authentication, @PathVariable Long jobId) {
        User user = currentUserService.resolve(authentication);
        return processingJobRepository.findByIdAndUser(jobId, user)
                .map(job -> {
                    // 진행 중인(PENDING/PROCESSING) job은 아직 파이프라인이 돌고 있어서 지금
                    // 지우면 완료 후 되살아나거나 orphan 데이터가 남을 수 있다 — 실패해서
                    // 더 이상 진행되지 않는 job만 삭제를 허용한다.
                    if (job.getStatus() != JobStatus.FAILED) {
                        return ResponseEntity.status(409).<Void>build();
                    }
                    processingJobRepository.delete(job);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
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
