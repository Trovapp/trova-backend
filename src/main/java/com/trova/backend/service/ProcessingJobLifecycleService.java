package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.ItineraryAssignment;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProcessingJobLifecycleService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final ProcessingJobRepository processingJobRepository;
    private final SavedPlaceRepository savedPlaceRepository;

    public ProcessingJobLifecycleService(
            ProcessingJobRepository processingJobRepository,
            SavedPlaceRepository savedPlaceRepository
    ) {
        this.processingJobRepository = processingJobRepository;
        this.savedPlaceRepository = savedPlaceRepository;
    }

    @Transactional
    public String markProcessing(Long jobId) {
        ProcessingJob job = getJob(jobId);
        job.markProcessing();
        return job.getSourceUrl();
    }

    @Transactional
    public void setTitle(Long jobId, String title) {
        getJob(jobId).setTitle(title);
    }

    @Transactional
    public void updateStage(Long jobId, ProcessingStage stage) {
        getJob(jobId).updateStage(stage);
    }

    @Transactional
    public void savePlace(Long jobId, ExtractedPlace extracted, GeocodingResult geocoded) {
        ProcessingJob job = getJob(jobId);
        // 카카오 검색으로 실제 존재가 확인된 이름(matchedName)이 있으면 그걸 우선한다 —
        // Gemini가 읽어낸 이름이 오탈자였어도 카카오 DB의 정확한 상호명으로 보정된다.
        String placeName = geocoded.matchedName() != null ? geocoded.matchedName() : extracted.name();
        savedPlaceRepository.save(new SavedPlace(
                job,
                job.getUser(),
                placeName,
                extracted.region(),
                extracted.category(),
                geocoded.latitude(),
                geocoded.longitude(),
                extracted.dayNumber(),
                extracted.orderInDay(),
                geocoded.phone(),
                geocoded.address(),
                geocoded.roadAddress(),
                geocoded.kakaoCategoryName(),
                geocoded.kakaoPlaceUrl()
        ));
    }

    @Transactional
    public void markDone(Long jobId) {
        getJob(jobId).markDone();
    }

    @Transactional
    public void applyItinerary(Long jobId, List<ItineraryAssignment> assignments) {
        ProcessingJob job = getJob(jobId);
        Map<Long, SavedPlace> byId = savedPlaceRepository.findByProcessingJob(job).stream()
                .collect(Collectors.toMap(SavedPlace::getId, place -> place));
        for (ItineraryAssignment assignment : assignments) {
            SavedPlace place = byId.get(assignment.id());
            if (place == null) {
                throw new IllegalStateException(
                        "일정 생성 결과에 존재하지 않는 장소 id가 포함되어 있습니다: " + assignment.id());
            }
            place.assignToDay(assignment.dayNumber(), assignment.orderInDay());
        }
    }

    @Transactional
    public void markFailed(Long jobId, String rawMessage) {
        getJob(jobId).markFailed(truncate(rawMessage));
    }

    private ProcessingJob getJob(Long jobId) {
        return processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MESSAGE_MAX_LENGTH
                ? message.substring(message.length() - ERROR_MESSAGE_MAX_LENGTH)
                : message;
    }
}
