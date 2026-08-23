package com.trova.backend.service;

import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.pipeline.PipelineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class PlaceExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PlaceExtractionService.class);

    private final ProcessingJobLifecycleService lifecycleService;
    private final PipelineRunner pipelineRunner;
    private final KakaoGeocodingService kakaoGeocodingService;

    public PlaceExtractionService(
            ProcessingJobLifecycleService lifecycleService,
            PipelineRunner pipelineRunner,
            KakaoGeocodingService kakaoGeocodingService
    ) {
        this.lifecycleService = lifecycleService;
        this.pipelineRunner = pipelineRunner;
        this.kakaoGeocodingService = kakaoGeocodingService;
    }

    @Async("pipelineTaskExecutor")
    public void process(Long jobId) {
        try {
            String sourceUrl = lifecycleService.markProcessing(jobId);
            log.info("ProcessingJob {} 파이프라인 시작: {}", jobId, sourceUrl);

            PipelineOutput output = pipelineRunner.run(sourceUrl, jobId);
            log.info("ProcessingJob {} 파이프라인 완료: {}개 장소 추출", jobId, output.places().size());

            lifecycleService.setTitle(jobId, output.title());

            // region-only 폴백이 같은 영상 안에서 이미 확정된 다른 장소와 좌표가 겹치는 걸
            // 막으려면, 지금까지 확정된 좌표를 계속 누적해서 geocode() 호출마다 넘겨줘야 한다.
            Set<String> usedCoordinateKeys = new HashSet<>();
            for (ExtractedPlace extracted : output.places()) {
                GeocodingResult geocoded = kakaoGeocodingService.geocode(
                        extracted.nameCandidates(), extracted.region(), usedCoordinateKeys);
                if (geocoded.latitude() != null) {
                    usedCoordinateKeys.add(geocoded.coordinateKey());
                }
                lifecycleService.savePlace(jobId, extracted, geocoded);
            }

            lifecycleService.markDone(jobId);
            log.info("ProcessingJob {} DONE", jobId);
        } catch (Exception e) {
            log.error("ProcessingJob {} 처리 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }
}
