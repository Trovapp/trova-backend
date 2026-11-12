package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoCategory;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.ItineraryAssignment;
import com.trova.backend.replan.GeoUtils;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 영상 분석 전용. 호출자는 같은 JVM의 작업 잠금을 잡고 있어야 한다(ADR 0024).
     * 끝난/삭제된 작업은 null, 재시작 후 중단된 PROCESSING은 다시 분석할 URL을 반환한다.
     * 일정 생성도 쓰는 markProcessing은 별도 유지한다.
     */
    public record AnalysisStart(String sourceUrl, LocalDateTime createdAt) { }

    @Transactional
    public String startAnalysis(Long jobId) {
        AnalysisStart start = startAnalysisWithMetadata(jobId);
        return start == null ? null : start.sourceUrl();
    }

    /** 시작 상태 확인에 사용한 생성 시각을 재조회 없이 큐 계측에 전달한다. */
    @Transactional
    public AnalysisStart startAnalysisWithMetadata(Long jobId) {
        ProcessingJob job = processingJobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() == JobStatus.DONE || job.getStatus() == JobStatus.FAILED
                || job.getAnalysisStatus() != null) {
            return null;
        }
        job.markProcessing();
        return new AnalysisStart(job.getSourceUrl(), job.getCreatedAt());
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
        savedPlaceRepository.save(createPlace(job, extracted, geocoded));
    }

    /** 외부 호출이 모두 끝난 뒤, 미완료 부분 결과 교체와 완료 상태를 함께 커밋한다. */
    @Transactional
    public boolean completeAnalysis(Long jobId, List<ExtractedPlace> extracted, List<GeocodingResult> geocoded) {
        return completeAnalysis(jobId, extracted, geocoded, Map.of());
    }

    /** nearbyBranches: 결과 순번 → 영상 근처의 같은 가게 지점 이름(다른 지점일 수 있다는 표시, #414). */
    @Transactional
    public boolean completeAnalysis(Long jobId, List<ExtractedPlace> extracted, List<GeocodingResult> geocoded,
                                    Map<Integer, String> nearbyBranches) {
        if (extracted.isEmpty() || extracted.size() != geocoded.size()) {
            throw new IllegalArgumentException("분석 결과와 지오코딩 결과의 크기가 일치해야 합니다");
        }
        ProcessingJob job = processingJobRepository.findForAnalysisCompletion(jobId)
                .orElseThrow(() -> new IllegalStateException("ProcessingJob을 찾을 수 없습니다: " + jobId));
        if (job.getStatus() != JobStatus.PROCESSING || job.getAnalysisStatus() != null) {
            return false;
        }
        savedPlaceRepository.deleteAll(savedPlaceRepository.findByProcessingJob(job));
        List<SavedPlace> relocatedBefore = relocatedInEarlierAnalyses(job);
        List<SavedPlace> places = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) {
            SavedPlace place = createPlace(job, extracted.get(i), geocoded.get(i));
            place.suggestNearbyBranch(nearbyBranches.get(i));
            places.add(place);
        }
        inheritRelocations(places, relocatedBefore);
        savedPlaceRepository.saveAll(places);
        job.markAnalysisDone();
        return true;
    }

    /** 같은 사용자가 같은 영상을 전에 분석한 결과 중 위치를 직접 바로잡은 장소. 최근 분석이 앞에 온다. */
    private List<SavedPlace> relocatedInEarlierAnalyses(ProcessingJob job) {
        if (job.getUser() == null) {
            return List.of();
        }
        String video = VideoKey.of(job.getSourceUrl());
        return savedPlaceRepository.findByUserOrderByCreatedAtDescIdDesc(job.getUser()).stream()
                .filter(p -> p.getRelocatedFromName() != null && p.getLatitude() != null)
                .filter(p -> !p.getProcessingJob().getId().equals(job.getId()))
                .filter(p -> VideoKey.of(p.getProcessingJob().getSourceUrl()).equals(video))
                .toList();
    }

    /**
     * 다시 분석한 장소가 예전에 바로잡은 장소와 같은 곳이면 그 위치를 이어받는다(#416, 사용자 결정 2026-11-12).
     * 먼저 이름이 같은 것끼리 잇고, 남은 것은 이름이 비슷하고(한쪽이 다른 쪽을 포함) 바로잡은 위치에서 가까울 때만 잇는다 —
     * 같은 영상인데 분석할 때마다 "청사포" / "청사포다릿돌전망대"처럼 이름이 다르게 뽑혀 이어받지 못했다(2차 수정 확인 QA, #418).
     * 한 번 바로잡은 위치는 한 장소만 이어받는다(비슷한 이름 둘이 같은 자리로 겹치지 않게).
     */
    static void inheritRelocations(List<SavedPlace> places, List<SavedPlace> relocatedBefore) {
        if (relocatedBefore.isEmpty()) {
            return;
        }
        Set<String> used = new HashSet<>();
        Set<SavedPlace> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SavedPlace place : places) {
            String name = normalizeName(place.getPlaceName());
            if (name.isEmpty()) {
                continue;
            }
            relocatedBefore.stream()
                    .filter(p -> name.equals(normalizeName(p.getRelocatedFromName()))
                            || name.equals(normalizeName(p.getPlaceName())))
                    .findFirst()
                    .ifPresent(p -> {
                        place.inheritRelocation(p);
                        used.add(normalizeName(p.getRelocatedFromName()));
                        done.add(place);
                    });
        }
        for (SavedPlace place : places) {
            if (done.contains(place) || place.getLatitude() == null || place.getLongitude() == null) {
                continue;
            }
            String name = normalizeName(place.getPlaceName());
            relocatedBefore.stream()
                    .filter(p -> !used.contains(normalizeName(p.getRelocatedFromName())))
                    .filter(p -> similarName(name, normalizeName(p.getRelocatedFromName()))
                            || similarName(name, normalizeName(p.getPlaceName())))
                    .filter(p -> GeoUtils.haversineKm(place.getLatitude(), place.getLongitude(),
                            p.getLatitude(), p.getLongitude()) <= SIMILAR_NAME_MAX_KM)
                    .findFirst()
                    .ifPresent(p -> {
                        place.inheritRelocation(p);
                        used.add(normalizeName(p.getRelocatedFromName()));
                    });
        }
    }

    /** 한쪽 이름이 다른 쪽을 포함하는지. 한 글자 이름은 어디에나 들어가므로 보지 않는다. */
    private static boolean similarName(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    static final double SIMILAR_NAME_MAX_KM = 1.0;

    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }

    private SavedPlace createPlace(ProcessingJob job, ExtractedPlace extracted, GeocodingResult geocoded) {
        // 카카오 검색으로 실제 존재가 확인된 이름(matchedName)이 있으면 그걸 우선한다 —
        // Gemini가 읽어낸 이름이 오탈자였어도 카카오 DB의 정확한 상호명으로 보정된다.
        String placeName = geocoded.matchedName() != null ? geocoded.matchedName() : extracted.name();
        SavedPlace place = new SavedPlace(
                job,
                job.getUser(),
                placeName,
                extracted.region(),
                KakaoCategory.resolve(geocoded.kakaoCategoryName(), extracted.category()),
                geocoded.latitude(),
                geocoded.longitude(),
                extracted.dayNumber(),
                extracted.orderInDay(),
                geocoded.phone(),
                geocoded.address(),
                geocoded.roadAddress(),
                geocoded.kakaoCategoryName(),
                geocoded.kakaoPlaceUrl()
        );
        place.applyVideoNotes(extracted.videoNotes());
        return place;
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

    /** 영상 분석이 끝났을 때 — 분석 결과와 시각도 남긴다(관리자 대시보드 처리 시간, #161). 일정 생성은 markDone. */
    @Transactional
    public void markAnalysisDone(Long jobId) {
        getJob(jobId).markAnalysisDone();
    }

    /** 영상 분석이 실패했을 때 — 분석 결과와 시각도 남긴다(#161). 일정 생성 실패는 markFailed. */
    @Transactional
    public void markAnalysisFailed(Long jobId, String rawMessage) {
        getJob(jobId).markAnalysisFailed(truncate(rawMessage));
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
