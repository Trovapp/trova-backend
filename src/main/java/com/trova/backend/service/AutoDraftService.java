package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.planner.PlanRequestParser;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 공유한 영상 분석이 끝나면 사용자가 따로 요청하지 않아도 일정 초안을 자동으로 만든다(#136).
 * 이미 같은 영상으로 만든 초안·여행이 있으면 건너뛰고, 분석 자체는 초안 생성 성공 여부와 상관없이 완료로 본다.
 */
@Service
public class AutoDraftService {

    private static final Logger log = LoggerFactory.getLogger(AutoDraftService.class);

    private boolean enabled;
    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;
    private final TripDraftRepository tripDraftRepository;
    private final TripService tripService;
    private final DraftJobQueue draftJobQueue;

    public AutoDraftService(@Value("${app.auto-draft.enabled:true}") boolean enabled,
                             ProcessingJobRepository processingJobRepository,
                             SavedPlaceRepository savedPlaceRepository,
                             TripDraftRepository tripDraftRepository,
                             TripService tripService,
                             DraftJobQueue draftJobQueue) {
        this.enabled = enabled;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripDraftRepository = tripDraftRepository;
        this.tripService = tripService;
        this.draftJobQueue = draftJobQueue;
    }

    /** 분석이 끝난 직후 부른다. 어떤 예외도 밖으로 던지지 않는다 — 분석 결과는 초안과 상관없이 완료다. */
    public void startFor(Long jobId) {
        try {
            Long draftId = create(jobId);
            if (draftId == null) {
                return;
            }
            try {
                // 운영은 Kafka(#390) — 초안을 만드는 중에 서버가 재시작돼도 다시 받아 끝까지 만든다.
                draftJobQueue.enqueue(draftId);
            } catch (TaskRejectedException e) {
                tripDraftRepository.deleteById(draftId);
                log.warn("ProcessingJob {} 자동 초안 대기열이 가득 차 건너뜀", jobId);
            }
        } catch (Exception e) {
            log.error("ProcessingJob {} 자동 초안 생성 실패", jobId, e);
        }
    }

    Long create(Long jobId) {
        if (!enabled) {
            return null;
        }
        ProcessingJob job = processingJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.DONE) {
            return null;
        }
        List<SavedPlace> places = savedPlaceRepository.findByProcessingJob(job);
        if (places.isEmpty()) {
            return null;
        }
        User user = job.getUser();
        if (tripService.findExistingTripForVideo(user, job).isPresent()) {
            return null;
        }
        String key = VideoKey.of(job.getSourceUrl());
        // 홈 목록 조회가 아니라 같은 영상의 자동 초안 중복 생성 방지다(#136, #450).
        // 승인·닫힘·실패한 자동 초안도 이력으로 남겨 다시 만들지 않으므로 autoCreated 기준을 유지한다.
        List<Long> autoDraftJobIds = tripDraftRepository.findByUserAndAutoCreatedTrue(user).stream()
                .flatMap(d -> d.getJobIds().stream())
                .toList();
        boolean exists = processingJobRepository.findAllById(autoDraftJobIds).stream()
                .anyMatch(j -> VideoKey.of(j.getSourceUrl()).equals(key));
        if (exists) {
            return null;
        }
        return tripDraftRepository.save(TripDraft.auto(user, List.of(jobId), autoMessage(places, job.getTitle()))).getId();
    }

    static String autoMessage(List<SavedPlace> places, String title) {
        int maxDay = places.stream().map(SavedPlace::getDayNumber).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        int rawDays = maxDay >= 2 ? maxDay : (title == null ? 1 : PlanRequestParser.parseRawDays(title).orElse(1));
        // 일차 구분이나 제목의 날수가 파서 최대치를 넘으면 그대로 메시지에 적지 않는다 — 넘는 메시지를
        // 다시 읽을 때 parseDays가 범위를 벗어났다며 빈 값을 돌려줘 Gemini를 부르고도 결국 1일로 깎인다(#136).
        int days = Math.min(rawDays, PlanRequestParser.MAX_DAYS);
        return days <= 1 ? "당일치기" : (days - 1) + "박 " + days + "일";
    }
}
