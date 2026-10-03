package com.trova.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.planner.OpeningHoursService;
import com.trova.backend.planner.PlanPlaceGatherer;
import com.trova.backend.planner.PlanRequestParser;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 일정 에이전트(#106). 1일차 범위: 요청 해석 → 고른 영상들의 장소 모으기 → (영상 지역이 멀면) 질문하고 멈춤 →
 * 영업시간 채우기 → 요약 저장. 초안 생성·검증·수정은 2·3일차에 이어 붙인다.
 */
@Service
public class TripPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TripPlannerService.class);
    public static final int MAX_VIDEOS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TripDraftRepository tripDraftRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final PlanRequestParser planRequestParser;
    private final OpeningHoursService openingHoursService;

    public TripPlannerService(TripDraftRepository tripDraftRepository, ProcessingJobRepository processingJobRepository,
                              SavedPlaceRepository savedPlaceRepository, PlanRequestParser planRequestParser,
                              OpeningHoursService openingHoursService) {
        this.tripDraftRepository = tripDraftRepository;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.planRequestParser = planRequestParser;
        this.openingHoursService = openingHoursService;
    }

    /** 고른 영상이 모두 이 사용자의 완료된 분석이어야 만든다. 아니면 빈 값(컨트롤러가 400). */
    public Optional<TripDraft> create(User user, List<Long> jobIds, String message) {
        if (jobIds == null || jobIds.isEmpty() || jobIds.size() > MAX_VIDEOS || message == null || message.isBlank()) {
            return Optional.empty();
        }
        List<Long> distinct = jobIds.stream().distinct().toList();
        for (Long jobId : distinct) {
            boolean ok = processingJobRepository.findById(jobId)
                    .filter(job -> job.getUser().getId().equals(user.getId()))
                    .filter(job -> job.getStatus() == JobStatus.DONE)
                    .isPresent();
            if (!ok) {
                return Optional.empty();
            }
        }
        return Optional.of(tripDraftRepository.save(new TripDraft(user, distinct, message.trim())));
    }

    @Async("planTaskExecutor")
    public void process(Long draftId) {
        TripDraft draft = tripDraftRepository.findById(draftId).orElse(null);
        if (draft == null) {
            return;
        }
        try {
            draft.markProcessing();
            tripDraftRepository.save(draft);

            PlanRequestParser.PlanRequest request = planRequestParser.parse(draft.getMessage());
            draft.applyRequest(request.days(), request.startDate(), request.source());

            List<PlanPlaceGatherer.VideoPlaces> videos = new ArrayList<>();
            for (Long jobId : draft.getJobIds()) {
                ProcessingJob job = processingJobRepository.findById(jobId).orElseThrow();
                videos.add(new PlanPlaceGatherer.VideoPlaces(jobId, job.getTitle(), savedPlaceRepository.findByProcessingJob(job)));
            }
            PlanPlaceGatherer.Gathered gathered = PlanPlaceGatherer.gather(videos);
            if (gathered.totalPlaces() == 0) {
                draft.markFailed("고른 영상에 저장된 장소가 없어요.");
                tripDraftRepository.save(draft);
                return;
            }
            if (gathered.question().isPresent()) {
                // 영업시간은 질문에 답한 뒤 실제로 쓸 장소만 받는다(무료 한도 보호).
                draft.markNeedsInput(gathered.question().get(), summaryJson(gathered, 0));
                tripDraftRepository.save(draft);
                return;
            }
            List<SavedPlace> all = gathered.videos().stream().flatMap(v -> v.places().stream()).toList();
            int calls = openingHoursService.fillMissing(all);
            draft.markReady(summaryJson(gathered, calls));
            tripDraftRepository.save(draft);
            log.info("TripDraft {} 1일차 준비 완료: 영상 {}개, 장소 {}곳, 영업시간 조회 {}번",
                    draftId, videos.size(), gathered.totalPlaces(), calls);
        } catch (Exception e) {
            log.error("TripDraft {} 처리 실패", draftId, e);
            // 앱에 내부 예외 문장을 보여주지 않는다(#95와 같은 원칙) — 원문은 로그에 있다.
            draft.markFailed("일정 초안을 만들지 못했어요. 잠시 후 다시 시도해주세요.");
            tripDraftRepository.save(draft);
        }
    }

    static String summaryJson(PlanPlaceGatherer.Gathered gathered, int hoursCalls) {
        List<Map<String, Object>> videos = new ArrayList<>();
        int withHours = 0;
        int hoursChecked = 0;
        for (PlanPlaceGatherer.VideoPlaces v : gathered.videos()) {
            List<Map<String, Object>> places = new ArrayList<>();
            for (SavedPlace p : v.places()) {
                if (p.getHoursCheckedAt() != null) {
                    hoursChecked++;
                }
                if (p.getOpeningPeriods() != null) {
                    withHours++;
                }
                Map<String, Object> place = new LinkedHashMap<>();
                place.put("id", p.getId());
                place.put("name", p.getPlaceName());
                place.put("category", p.getCategory());
                place.put("hasCoords", p.getLatitude() != null);
                place.put("hasHours", p.getOpeningPeriods() != null);
                places.add(place);
            }
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("jobId", v.jobId());
            video.put("title", v.title());
            video.put("places", places);
            videos.add(video);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("videos", videos);
        summary.put("totalPlaces", gathered.totalPlaces());
        summary.put("placesWithCoords", gathered.placesWithCoords());
        summary.put("maxVideoDistanceKm", gathered.maxVideoDistanceKm());
        summary.put("hoursChecked", hoursChecked);
        summary.put("placesWithHours", withHours);
        summary.put("hoursCallsThisRun", hoursCalls);
        try {
            return MAPPER.writeValueAsString(summary);
        } catch (Exception e) {
            return "{}";
        }
    }
}
