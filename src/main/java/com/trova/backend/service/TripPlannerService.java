package com.trova.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.planner.OpeningHoursService;
import com.trova.backend.planner.PlanPlaceGatherer;
import com.trova.backend.planner.PlanRequestParser;
import com.trova.backend.planner.TripPlanGraph;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 일정 에이전트(#106): 요청 해석 → 고른 영상들의 장소 모으기 → (영상 지역이 멀면) 질문하고 멈춤 →
 * 영업시간 채우기 → 초안 생성·검증·수정(TripPlanGraph) → 저장. 승인해야 여행이 된다(4일차).
 */
@Service
public class TripPlannerService {

    private static final Logger log = LoggerFactory.getLogger(TripPlannerService.class);
    public static final int MAX_VIDEOS = 5;
    public static final String ANSWER_SPLIT = "SPLIT";
    public static final String ANSWER_ONLY = "ONLY";
    static final String SPLIT_NOTE = "영상 지역이 서로 멀리 떨어져 있어요. 하루에는 한 지역의 장소만 넣고, 지역별로 날을 나누세요.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TripDraftRepository tripDraftRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final PlanRequestParser planRequestParser;
    private final OpeningHoursService openingHoursService;
    private final TripPlanGraph tripPlanGraph;

    public TripPlannerService(TripDraftRepository tripDraftRepository, ProcessingJobRepository processingJobRepository,
                              SavedPlaceRepository savedPlaceRepository, PlanRequestParser planRequestParser,
                              OpeningHoursService openingHoursService, TripPlanGraph tripPlanGraph) {
        this.tripDraftRepository = tripDraftRepository;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.planRequestParser = planRequestParser;
        this.openingHoursService = openingHoursService;
        this.tripPlanGraph = tripPlanGraph;
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

    public enum AnswerOutcome { OK, NOT_FOUND, NOT_WAITING, BAD_REQUEST }

    /**
     * 시작 전 질문(영상 지역이 멀다)에 답한다. SPLIT: 모든 영상으로 지역별로 날을 나눈다. ONLY: 고른 영상만 쓴다.
     * 답하면 다시 대기열로 돌아가며, 호출한 쪽이 커밋 뒤 process를 부른다. 두 번 눌러도 한 번만 받게 행을 잠근다.
     */
    @Transactional
    public AnswerOutcome answer(User user, Long draftId, String choice, List<Long> jobIds) {
        TripDraft draft = tripDraftRepository.findByIdForUpdate(draftId)
                .filter(d -> d.getUser().getId().equals(user.getId()))
                .orElse(null);
        if (draft == null) {
            return AnswerOutcome.NOT_FOUND;
        }
        if (draft.getStatus() != TripDraftStatus.NEEDS_INPUT) {
            return AnswerOutcome.NOT_WAITING;
        }
        if (ANSWER_SPLIT.equals(choice)) {
            draft.answer(ANSWER_SPLIT, null);
        } else if (ANSWER_ONLY.equals(choice) && jobIds != null && !jobIds.isEmpty()
                && draft.getJobIds().containsAll(jobIds)) {
            draft.answer(ANSWER_ONLY, jobIds.stream().distinct().toList());
        } else {
            return AnswerOutcome.BAD_REQUEST;
        }
        tripDraftRepository.save(draft);
        return AnswerOutcome.OK;
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

            // 질문에 답하고 다시 들어온 경우 요청은 이미 읽었다 — Gemini를 다시 부르지 않는다.
            PlanRequestParser.PlanRequest request;
            if (draft.getDays() != null) {
                request = new PlanRequestParser.PlanRequest(draft.getDays(), draft.getStartDate(), draft.getRequestSource());
            } else {
                request = planRequestParser.parse(draft.getMessage());
                draft.applyRequest(request.days(), request.startDate(), request.source());
                draft.addGeminiCalls("AI".equals(request.source()) || "DEFAULT".equals(request.source()) ? 1 : 0);
            }
            boolean split = ANSWER_SPLIT.equals(draft.getAnswer());

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
            if (gathered.question().isPresent() && !split) {
                // 영업시간은 질문에 답한 뒤 실제로 쓸 장소만 받는다(무료 한도 보호).
                draft.markNeedsInput(gathered.question().get(), summaryJson(gathered, 0));
                tripDraftRepository.save(draft);
                return;
            }
            List<SavedPlace> all = gathered.videos().stream().flatMap(v -> v.places().stream()).toList();
            // 숙소는 방문지가 아니라 일정 칸에 넣지 않고 숙소 안내에만 쓴다 — 영업시간도 묻지 않는다(무료 한도 보호).
            List<SavedPlace> lodging = all.stream().filter(TripPlannerService::isLodging).toList();
            List<SavedPlace> visits = all.stream().filter(p -> !isLodging(p)).toList();
            if (visits.isEmpty()) {
                draft.markFailed("고른 영상에 숙소 말고 갈 장소가 없어요.");
                tripDraftRepository.save(draft);
                return;
            }
            // 자동 초안은 공유마다 돌아서 유료 구간이 있는 영업시간 조회를 하지 않는다(#136, 비용 0원 원칙)
            // — 사용자가 '조건 바꿔 다시 짜기'로 만든 초안은 그대로 조회
            int calls = draft.isAutoCreated() ? 0 : openingHoursService.fillMissing(visits);
            TripPlanGraph.Outcome outcome = tripPlanGraph.run(request.days(), request.startDate(), visits,
                    split ? List.of(SPLIT_NOTE) : List.of());
            draft.addGeminiCalls(outcome.geminiCalls());
            if (outcome.draft().isEmpty()) {
                log.warn("TripDraft {} 초안 생성 실패: {}", draftId, outcome.failure());
                draft.markFailed("일정 초안을 만들지 못했어요. 잠시 후 다시 시도해주세요.");
                tripDraftRepository.save(draft);
                return;
            }
            DraftGenerator.Draft plan = withLodging(outcome.draft().get(), lodging);
            if (draft.isAutoCreated()) {
                plan = withAutoDraftNotes(plan);
            }
            draft.markReady(summaryJson(gathered, calls),
                    DraftGenerator.toJson(plan, outcome.fixes(), outcome.problems()));
            tripDraftRepository.save(draft);
            log.info("TripDraft {} 초안 완료: 영상 {}개, 장소 {}곳, 영업시간 조회 {}번, Gemini {}번, AI 수정 {}번, ERROR {}→{}건",
                    draftId, videos.size(), gathered.totalPlaces(), calls, draft.getGeminiCalls(),
                    outcome.aiRepairs(), outcome.firstErrors(), outcome.finalErrors());
        } catch (Exception e) {
            log.error("TripDraft {} 처리 실패", draftId, e);
            // 앱에 내부 예외 문장을 보여주지 않는다(#95와 같은 원칙) — 원문은 로그에 있다.
            draft.markFailed("일정 초안을 만들지 못했어요. 잠시 후 다시 시도해주세요.");
            tripDraftRepository.save(draft);
        }
    }

    static final String AUTO_HOURS_NOTE = "자동으로 짠 초안이라 영업시간은 아직 확인하지 않았어요. '조건 바꿔 다시 짜기'로 다시 짜면 확인해요.";

    /**
     * 자동 초안은 영업시간을 일부러 조회하지 않아(#136) "영업시간을 확인하지 못한 장소가 N곳" 안내가 거의 항상 붙어
     * 실패한 것처럼 보였다(#139) — 이유와 확인하는 방법을 알려주는 문장으로 바꾼다.
     */
    static DraftGenerator.Draft withAutoDraftNotes(DraftGenerator.Draft plan) {
        List<String> notes = plan.assumptions().stream()
                .map(note -> note.startsWith(DraftGenerator.NO_HOURS_NOTE_PREFIX) ? AUTO_HOURS_NOTE : note)
                .toList();
        return new DraftGenerator.Draft(plan.days(), plan.excluded(), plan.lodging(), notes);
    }

    private static boolean isLodging(SavedPlace p) {
        return "lodging".equals(p.getCategory());
    }

    /** 숙소 안내를 최종 동선 기준으로 다시 만든다. 당일 일정이면 영상 속 숙소는 뺀 장소로 알린다. */
    static DraftGenerator.Draft withLodging(DraftGenerator.Draft plan, List<SavedPlace> lodging) {
        List<DraftGenerator.Excluded> excluded = new ArrayList<>(plan.excluded());
        if (plan.days().size() == 1) {
            lodging.forEach(p -> excluded.add(new DraftGenerator.Excluded(p.getId(), p.getPlaceName(),
                    "당일 일정이라 숙소는 넣지 않았어요.")));
        }
        return new DraftGenerator.Draft(plan.days(), excluded, DraftGenerator.lodging(plan.days(), lodging), plan.assumptions());
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
