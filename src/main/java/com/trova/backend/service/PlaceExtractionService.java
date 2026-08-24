package com.trova.backend.service;

import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PlaceVerification;
import com.trova.backend.pipeline.PlaceVerificationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlaceExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PlaceExtractionService.class);

    // 이 값 미만이면 Gemini가 이름 자체를 확신하지 못했다고 보고 검증 대상에 포함한다.
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;

    private final ProcessingJobLifecycleService lifecycleService;
    private final PipelineRunner pipelineRunner;
    private final KakaoGeocodingService kakaoGeocodingService;
    private final PlaceVerificationRunner placeVerificationRunner;

    public PlaceExtractionService(
            ProcessingJobLifecycleService lifecycleService,
            PipelineRunner pipelineRunner,
            KakaoGeocodingService kakaoGeocodingService,
            PlaceVerificationRunner placeVerificationRunner
    ) {
        this.lifecycleService = lifecycleService;
        this.pipelineRunner = pipelineRunner;
        this.kakaoGeocodingService = kakaoGeocodingService;
        this.placeVerificationRunner = placeVerificationRunner;
    }

    @Async("pipelineTaskExecutor")
    public void process(Long jobId) {
        try {
            String sourceUrl = lifecycleService.markProcessing(jobId);
            log.info("ProcessingJob {} 파이프라인 시작: {}", jobId, sourceUrl);

            PipelineOutput output = pipelineRunner.run(sourceUrl, jobId);
            log.info("ProcessingJob {} 파이프라인 완료: {}개 장소 추출", jobId, output.places().size());

            lifecycleService.setTitle(jobId, output.title());

            List<ExtractedPlace> extractedList = output.places();
            List<GeocodingResult> geocodedList = new ArrayList<>();

            // region-only 폴백이 같은 영상 안에서 이미 확정된 다른 장소와 좌표가 겹치는 걸
            // 막으려면, 지금까지 확정된 좌표를 계속 누적해서 geocode() 호출마다 넘겨줘야 한다.
            Set<String> usedCoordinateKeys = new HashSet<>();
            for (ExtractedPlace extracted : extractedList) {
                GeocodingResult geocoded = kakaoGeocodingService.geocode(
                        extracted.nameCandidates(), extracted.region(), usedCoordinateKeys);
                if (geocoded.latitude() != null) {
                    usedCoordinateKeys.add(geocoded.coordinateKey());
                }
                geocodedList.add(geocoded);
            }

            geocodedList = verifyUncertainMatches(jobId, extractedList, geocodedList);

            for (int i = 0; i < extractedList.size(); i++) {
                lifecycleService.savePlace(jobId, extractedList.get(i), geocodedList.get(i));
            }

            lifecycleService.markDone(jobId);
            log.info("ProcessingJob {} DONE", jobId);
        } catch (Exception e) {
            log.error("ProcessingJob {} 처리 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }

    /**
     * 좌표는 있지만 확신하기 어려운 매칭(region-only 폴백이거나 원래 이름 확신도가 낮음)만
     * 모아서 Gemini로 한 번에 검증하고, 검증에 실패한 것만 좌표를 비운다. 검증 대상이 없으면
     * 호출 자체를 생략한다(비용 절약).
     */
    private List<GeocodingResult> verifyUncertainMatches(
            Long jobId, List<ExtractedPlace> extractedList, List<GeocodingResult> geocodedList
    ) {
        List<PlaceVerificationRunner.VerificationCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < extractedList.size(); i++) {
            ExtractedPlace extracted = extractedList.get(i);
            GeocodingResult geocoded = geocodedList.get(i);
            if (geocoded.latitude() == null) {
                continue;
            }
            boolean isFallback = geocoded.matchedName() == null;
            boolean isLowConfidence =
                    extracted.confidence() == null || extracted.confidence() < LOW_CONFIDENCE_THRESHOLD;
            if (isFallback || isLowConfidence) {
                candidates.add(new PlaceVerificationRunner.VerificationCandidate(
                        i, extracted.name(), extracted.region()));
            }
        }

        if (candidates.isEmpty()) {
            return geocodedList;
        }

        log.info("ProcessingJob {} 장소 {}개 검증 시작", jobId, candidates.size());
        List<PlaceVerification> verdicts = placeVerificationRunner.run(candidates, jobId);
        Map<Integer, Boolean> validByIndex = verdicts.stream()
                .collect(Collectors.toMap(PlaceVerification::index, PlaceVerification::valid));

        List<GeocodingResult> result = new ArrayList<>(geocodedList);
        for (Map.Entry<Integer, Boolean> entry : validByIndex.entrySet()) {
            if (!entry.getValue()) {
                log.info("ProcessingJob {} 장소 검증 실패로 좌표 폐기: {}",
                        jobId, extractedList.get(entry.getKey()).name());
                result.set(entry.getKey(), GeocodingResult.empty());
            }
        }
        return result;
    }
}
