package com.trova.backend.service;

import com.trova.backend.push.TripDraftReadyEvent;
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
import com.trova.backend.repository.JobSourceUrlRow;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final Object[] draftLocks = createDraftLocks();

    private final TripDraftRepository tripDraftRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final PlanRequestParser planRequestParser;
    private final OpeningHoursService openingHoursService;
    private final TripPlanGraph tripPlanGraph;
    private final ApplicationEventPublisher events;
    private final DraftProcessingService processing;

    public TripPlannerService(TripDraftRepository tripDraftRepository, ProcessingJobRepository processingJobRepository,
                              SavedPlaceRepository savedPlaceRepository, PlanRequestParser planRequestParser,
                              OpeningHoursService openingHoursService, TripPlanGraph tripPlanGraph,
                              ApplicationEventPublisher events, DraftProcessingService processing) {
        this.tripDraftRepository = tripDraftRepository;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.planRequestParser = planRequestParser;
        this.openingHoursService = openingHoursService;
        this.tripPlanGraph = tripPlanGraph;
        this.events = events;
        this.processing = processing;
    }

    static boolean validDraftInput(List<Long> jobIds, String message) {
        return jobIds != null && !jobIds.isEmpty() && jobIds.size() <= MAX_VIDEOS
                && jobIds.stream().noneMatch(Objects::isNull) && message != null && !message.isBlank();
    }

    /**
     * replacesDraftId: "다시 짜기"로 대신할 예전 초안. 그 초안이 이 사용자의 것이고 홈에 보이던 초안이면 새 초안도 홈에
     * 보이게 한다 — 다시 짜기에 성공해도 홈·내 여행에서 초안이 사라졌다(4차 수정 확인 QA, #424). 아니면 무시한다.
     */
    Optional<TripDraft> prepareDraft(User user, List<Long> jobIds, String message, Long replacesDraftId) {
        if (!validDraftInput(jobIds, message)) {
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
        TripDraft draft = new TripDraft(user, distinct, message.trim());
        if (replacesDraftId != null) {
            tripDraftRepository.findById(replacesDraftId)
                    .filter(old -> old.getUser().getId().equals(user.getId()))
                    .ifPresent(draft::recordReplacementOf);
        }
        return Optional.of(draft);
    }

    /**
     * 초안의 영상마다 지금의 대표 분석(장소가 남아 있는 가장 최근 작업) 번호. 같은 영상을 다시 분석하면 새 작업이
     * 생기는데 초안은 예전 작업을 가리켜, "조건 바꿔 다시 짜기"에서 영상이 0개 선택되고 새로 바로잡은 위치도 초안과
     * 이어지지 않았다(수정 확인 QA 2026-11-12, #416). 초안 내용은 건드리지 않고 앱이 다시 짤 때 이 번호를 쓴다.
     * 만드는 중(폴링)이거나 이미 여행이 된 초안은 계산하지 않고 원래 번호를 돌려준다.
     * 사용자의 저장 장소 전체를 엔티티로 읽으면 장소 100개에 엔티티 204개가 실렸다 — 작업 번호·주소만 읽는다(#473).
     * 두 조회를 한 읽기 트랜잭션(커넥션 하나)에서 실행하려고 트랜잭션은 유지한다.
     */
    @Transactional(readOnly = true)
    public List<Long> currentJobIds(TripDraft draft) {
        List<Long> jobIds = draft.getJobIds();
        if (draft.getTripId() != null
                || (draft.getStatus() != TripDraftStatus.READY && draft.getStatus() != TripDraftStatus.NEEDS_INPUT)) {
            return jobIds;
        }
        Map<Long, String> videoByJob = new HashMap<>();
        processingJobRepository.findSourceUrlRowsByIdIn(jobIds)
                .forEach(job -> videoByJob.put(job.id(), VideoKey.of(job.sourceUrl())));
        Map<String, Long> latestByVideo = new HashMap<>();
        for (JobSourceUrlRow job : savedPlaceRepository.findJobsWithPlacesByUserId(draft.getUser().getId())) {
            latestByVideo.merge(VideoKey.of(job.sourceUrl()), job.id(), Math::max);
        }
        return jobIds.stream()
                .map(id -> latestByVideo.getOrDefault(videoByJob.get(id), id))
                .distinct()
                .toList();
    }

    /** 사용자의 분석 작업 전체와, 영상마다 가장 최근에 완료된 분석. */
    public record Analyses(Map<Long, ProcessingJob> byId, Map<String, ProcessingJob> latestByVideo) {
        /** 같은 영상의 가장 최근 완료된 분석. 없으면 그 작업 그대로. */
        public ProcessingJob latest(ProcessingJob job) {
            return latestByVideo.getOrDefault(VideoKey.of(job.getSourceUrl()), job);
        }
    }

    /**
     * 홈 카드가 예전 분석 번호를 가리키면 앱이 영상을 못 찾아 장소 수와 썸네일이 빠졌다(#424). 사용자의 작업 목록을
     * 한 번만 읽어 초안의 영상과 최신 분석을 같이 찾는다(쿼리 수를 늘리지 않는다).
     */
    @Transactional(readOnly = true)
    public Analyses analysesOf(Long userId) {
        Map<Long, ProcessingJob> byId = new HashMap<>();
        Map<String, ProcessingJob> latestByVideo = new HashMap<>();
        for (ProcessingJob job : processingJobRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            byId.put(job.getId(), job);
            if (job.getStatus() == JobStatus.DONE) {
                latestByVideo.merge(VideoKey.of(job.getSourceUrl()), job, (a, b) -> a.getId() > b.getId() ? a : b);
            }
        }
        return new Analyses(byId, latestByVideo);
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
        processNow(draftId);
    }

    /**
     * 부른 스레드에서 끝까지 만든다. Kafka 리스너(#390)는 처리가 끝난 뒤 메시지를 완료로 표시해야 서버가 중간에 죽었을 때
     * 같은 초안을 다시 받으므로 @Async를 거치지 않고 이걸 부른다.
     * 같은 초안은 서버 한 대 안에서 한 번에 하나만 돈다(Kafka 확인이 늦어 서버 안 대기열과 두 경로로 들어온 경우, ADR 0024와 같은 방식).
     * 다시 받았을 때 이미 끝났거나(READY·APPROVED·FAILED) 사용자 답을 기다리는(NEEDS_INPUT) 초안은 Gemini를 다시 부르지 않고 건너뛴다.
     * PROCESSING은 처리 중 서버가 죽은 경우라 이어서 만든다.
     */
    public void processNow(Long draftId) {
        synchronized (draftLocks[Math.floorMod(draftId.hashCode(), draftLocks.length)]) {
            processSerially(draftId);
        }
    }

    private void processSerially(Long draftId) {
        TripDraft draft = processing.start(draftId).orElse(null);
        if (draft == null) {
            return;
        }
        try {
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
                processing.finish(draftId, draft);
                return;
            }
            if (gathered.question().isPresent() && !split) {
                // 영업시간은 질문에 답한 뒤 실제로 쓸 장소만 받는다(무료 한도 보호).
                draft.markNeedsInput(gathered.question().get(), summaryJson(gathered, 0));
                processing.finish(draftId, draft);
                return;
            }
            List<SavedPlace> all = gathered.videos().stream().flatMap(v -> v.places().stream()).toList();
            // 숙소는 방문지가 아니라 일정 칸에 넣지 않고 숙소 안내에만 쓴다 — 영업시간도 묻지 않는다(무료 한도 보호).
            List<SavedPlace> lodging = all.stream().filter(TripPlannerService::isLodging).toList();
            List<SavedPlace> visits = all.stream().filter(p -> !isLodging(p)).toList();
            if (visits.isEmpty()) {
                draft.markFailed("고른 영상에 숙소 말고 갈 장소가 없어요.");
                processing.finish(draftId, draft);
                return;
            }
            // 자동 초안은 공유마다 돌아서 유료 구간이 있는 영업시간 조회를 하지 않는다(#136, 비용 0원 원칙)
            // — 사용자가 '조건 바꿔 다시 짜기'로 만든 초안은 그대로 조회
            int calls = draft.isAutoCreated() ? 0 : openingHoursService.fillMissing(visits);
            TripPlanGraph.Outcome outcome = tripPlanGraph.run(request.days(), request.startDate(), visits,
                    planNotes(split, draft));
            draft.addGeminiCalls(outcome.geminiCalls());
            if (outcome.draft().isEmpty()) {
                log.warn("TripDraft {} 초안 생성 실패: {}", draftId, outcome.failure());
                draft.markFailed("일정 초안을 만들지 못했어요. 잠시 후 다시 시도해주세요.");
                processing.finish(draftId, draft);
                return;
            }
            DraftGenerator.Draft plan = withLodging(outcome.draft().get(), lodging);
            if (draft.isAutoCreated()) {
                plan = withAutoDraftNotes(plan);
            }
            draft.markReady(summaryJson(gathered, calls),
                    DraftGenerator.toJson(plan, outcome.fixes(), outcome.problems()));
            if (!processing.finish(draftId, draft)) {
                return;
            }
            if (draft.isAutoCreated()) {
                // 공유만 하고 앱을 닫은 사람에게 준비됐다고 알린다(#185, ADR 0016). 직접 만든 초안은 화면에서 기다리고 있다.
                int placeCount = plan.days().stream().mapToInt(d -> d.items().size()).sum();
                try {
                    events.publishEvent(new TripDraftReadyEvent(draft.getUser().getId(), draftId, placeCount, plan.days().size()));
                } catch (RuntimeException e) {
                    // 알림을 못 맡겨도(발송 대기열 가득 등) 이미 저장한 초안은 그대로 READY다 — 아래 catch로 가면 FAILED로 덮인다.
                    log.warn("TripDraft {} 준비 알림을 맡기지 못함: {}", draftId, e.getClass().getSimpleName());
                }
            }
            log.info("TripDraft {} 초안 완료: 영상 {}개, 장소 {}곳, 영업시간 조회 {}번, Gemini {}번, AI 수정 {}번, ERROR {}→{}건",
                    draftId, videos.size(), gathered.totalPlaces(), calls, draft.getGeminiCalls(),
                    outcome.aiRepairs(), outcome.firstErrors(), outcome.finalErrors());
        } catch (Exception e) {
            log.error("TripDraft {} 처리 실패", draftId, e);
            // 앱에 내부 예외 문장을 보여주지 않는다(#95와 같은 원칙) — 원문은 로그에 있다.
            draft.markFailed("일정 초안을 만들지 못했어요. 잠시 후 다시 시도해주세요.");
            processing.finish(draftId, draft);
        }
    }

    private static Object[] createDraftLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
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

    /**
     * 생성 프롬프트에 붙일 메모. 사용자가 직접 만든 초안이면 날짜·기간을 뗀 "더 바라는 점"을 넣는다(#414) —
     * 장소는 영상 속 목록에서만 고르므로(목록 밖 장소 금지) 목록 안에서 반영하라고 적는다.
     */
    static List<String> planNotes(boolean split, TripDraft draft) {
        List<String> notes = new ArrayList<>();
        if (split) {
            notes.add(SPLIT_NOTE);
        }
        String preference = draft.isAutoCreated() ? "" : PlanRequestParser.preference(draft.getMessage());
        if (!preference.isBlank()) {
            notes.add("사용자가 바라는 점: \"" + preference + "\" — 아래 장소 목록 안에서 최대한 반영하세요"
                    + "(예: 식당을 점심·저녁 시간대로 옮기기). 목록에 없는 장소가 필요한 요청이면 그 부분은 무시하세요.");
        }
        return notes;
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
