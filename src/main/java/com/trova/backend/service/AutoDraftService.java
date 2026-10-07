package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.User;
import com.trova.backend.planner.PlanRequestParser;
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
import java.util.Optional;

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
    private final TripPlannerService tripPlannerService;

    public AutoDraftService(@Value("${app.auto-draft.enabled:true}") boolean enabled,
                             ProcessingJobRepository processingJobRepository,
                             SavedPlaceRepository savedPlaceRepository,
                             TripDraftRepository tripDraftRepository,
                             TripService tripService,
                             TripPlannerService tripPlannerService) {
        this.enabled = enabled;
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
        this.tripDraftRepository = tripDraftRepository;
        this.tripService = tripService;
        this.tripPlannerService = tripPlannerService;
    }

    /** 분석이 끝난 직후 부른다. 어떤 예외도 밖으로 던지지 않는다 — 분석 결과는 초안과 상관없이 완료다. */
    public void startFor(Long jobId) {
        try {
            Long draftId = create(jobId);
            if (draftId == null) {
                return;
            }
            try {
                tripPlannerService.process(draftId);
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
        boolean exists = tripDraftRepository.findByUserAndAutoCreatedTrue(user).stream()
                .flatMap(d -> d.getJobIds().stream())
                .map(processingJobRepository::findById).flatMap(Optional::stream)
                .anyMatch(j -> VideoKey.of(j.getSourceUrl()).equals(key));
        if (exists) {
            return null;
        }
        return tripDraftRepository.save(TripDraft.auto(user, List.of(jobId), autoMessage(places, job.getTitle()))).getId();
    }

    static String autoMessage(List<SavedPlace> places, String title) {
        int maxDay = places.stream().map(SavedPlace::getDayNumber).filter(Objects::nonNull).max(Integer::compare).orElse(0);
        int days = maxDay >= 2 ? maxDay : (title == null ? 1 : PlanRequestParser.parseDays(title).orElse(1));
        return days <= 1 ? "당일치기" : (days - 1) + "박 " + days + "일";
    }
}
