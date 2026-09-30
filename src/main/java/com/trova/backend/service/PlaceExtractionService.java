package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.geocoding.KakaoKeywordSearchResponse;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PlaceSelection;
import com.trova.backend.pipeline.PlaceSelectionRunner;
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
    private final PlaceSelectionRunner placeSelectionRunner;
    private final PlaceVerificationRunner placeVerificationRunner;
    private final FoundPlaceNameStore foundPlaceNameStore;

    public PlaceExtractionService(
            ProcessingJobLifecycleService lifecycleService,
            PipelineRunner pipelineRunner,
            KakaoGeocodingService kakaoGeocodingService,
            PlaceSelectionRunner placeSelectionRunner,
            PlaceVerificationRunner placeVerificationRunner,
            FoundPlaceNameStore foundPlaceNameStore
    ) {
        this.lifecycleService = lifecycleService;
        this.pipelineRunner = pipelineRunner;
        this.kakaoGeocodingService = kakaoGeocodingService;
        this.placeSelectionRunner = placeSelectionRunner;
        this.placeVerificationRunner = placeVerificationRunner;
        this.foundPlaceNameStore = foundPlaceNameStore;
    }

    @Async("pipelineTaskExecutor")
    public void process(Long jobId) {
        try {
            String sourceUrl = lifecycleService.markProcessing(jobId);
            log.info("ProcessingJob {} 파이프라인 시작: {}", jobId, sourceUrl);

            lifecycleService.updateStage(jobId, ProcessingStage.EXTRACTING);
            // 분석이 끝나기 전에 제목·찾은 장소 이름을 앱에 보여줄 수 있게 바로 반영한다(#51).
            PipelineOutput output = pipelineRunner.run(sourceUrl, jobId, progress -> {
                if (progress.title() != null) {
                    lifecycleService.setTitle(jobId, progress.title());
                }
                if (progress.placeNames() != null) {
                    foundPlaceNameStore.put(jobId, progress.placeNames());
                }
            });
            log.info("ProcessingJob {} 파이프라인 완료: {}개 장소 추출", jobId, output.places().size());

            lifecycleService.setTitle(jobId, output.title());

            // 장소가 0곳이면 완료로 끝내지 않는다 — 결과 화면에 보여줄 게 없고, 영상 기록(저장된 장소 기준)에도
            // 남지 않아 사용자가 무슨 일이 있었는지 알 수 없었다(#55). 실패로 남겨 처리 중 목록에서 이유를 보여준다.
            if (output.places().isEmpty()) {
                log.info("ProcessingJob {} 장소 0곳 — 장소 없음으로 실패 처리", jobId);
                lifecycleService.markFailed(jobId, ProcessingJob.NO_PLACES_MESSAGE);
                return;
            }

            List<ExtractedPlace> extractedList = output.places();
            List<GeocodingResult> geocodedList = new ArrayList<>();

            // region-only 폴백이 같은 영상 안에서 이미 확정된 다른 장소와 좌표가 겹치는 걸
            // 막으려면, 지금까지 확정된 좌표를 계속 누적해서 geocode() 호출마다 넘겨줘야 한다.
            lifecycleService.updateStage(jobId, ProcessingStage.GEOCODING);
            Set<String> usedCoordinateKeys = new HashSet<>();
            for (ExtractedPlace extracted : extractedList) {
                GeocodingResult geocoded = kakaoGeocodingService.geocode(
                        extracted.nameCandidates(), extracted.region(), extracted.address(), usedCoordinateKeys, jobId);
                if (geocoded.latitude() != null) {
                    usedCoordinateKeys.add(geocoded.coordinateKey());
                }
                geocodedList.add(geocoded);
            }

            lifecycleService.updateStage(jobId, ProcessingStage.SELECTING);
            geocodedList = selectAmongAlternatives(jobId, extractedList, geocodedList);

            lifecycleService.updateStage(jobId, ProcessingStage.VERIFYING);
            geocodedList = verifyUncertainMatches(jobId, extractedList, geocodedList);

            lifecycleService.updateStage(jobId, ProcessingStage.SAVING);
            for (int i = 0; i < extractedList.size(); i++) {
                lifecycleService.savePlace(jobId, extractedList.get(i), geocodedList.get(i));
            }

            lifecycleService.markDone(jobId);
            log.info("ProcessingJob {} DONE", jobId);
        } catch (Exception e) {
            log.error("ProcessingJob {} 처리 실패", jobId, e);
            // 하루 한도 소진은 앱이 원인과 다시 가능한 시점을 알려줄 수 있게 정해진 문구로 남긴다(#63).
            boolean dailyQuota = e.getMessage() != null && e.getMessage().contains(PipelineRunner.DAILY_QUOTA_MARKER);
            lifecycleService.markFailed(jobId, dailyQuota ? ProcessingJob.AI_QUOTA_MESSAGE : e.getMessage());
        } finally {
            foundPlaceNameStore.clear(jobId);
        }
    }

    /**
     * 카카오 검색이 후보를 여러 개 반환한 장소만 모아서 Gemini로 한 번에 "문맥상 제일
     * 맞는 후보"를 고르게 한다. candidateIndex 0(카카오 1등, 원래 채택값)을 그대로
     * 유지하면 아무 변경도 하지 않고, 다른 후보를 고르면 그 후보의 좌표/이름/주소로
     * 교체하며, 어느 후보도 안 맞다고 판단하면(null) 좌표를 비운다(틀린 좌표보다
     * 없는 게 낫다는 원칙과 동일). 재검토 대상이 없으면 호출 자체를 생략한다.
     */
    private List<GeocodingResult> selectAmongAlternatives(
            Long jobId, List<ExtractedPlace> extractedList, List<GeocodingResult> geocodedList
    ) {
        List<PlaceSelectionRunner.SelectionCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < extractedList.size(); i++) {
            ExtractedPlace extracted = extractedList.get(i);
            GeocodingResult geocoded = geocodedList.get(i);
            if (geocoded.latitude() == null || geocoded.alternativeCandidates().isEmpty()) {
                continue;
            }

            List<PlaceSelectionRunner.CandidateOption> options = new ArrayList<>();
            options.add(new PlaceSelectionRunner.CandidateOption(
                    0, geocoded.matchedName(), geocoded.address(), geocoded.roadAddress(), geocoded.kakaoCategoryName()));
            int candidateIndex = 1;
            for (KakaoKeywordSearchResponse.Document alt : geocoded.alternativeCandidates()) {
                options.add(new PlaceSelectionRunner.CandidateOption(
                        candidateIndex++, alt.placeName(), alt.addressName(), alt.roadAddressName(), alt.categoryName()));
            }

            candidates.add(new PlaceSelectionRunner.SelectionCandidate(i, extracted.name(), extracted.region(), options));
        }

        if (candidates.isEmpty()) {
            return geocodedList;
        }

        log.info("ProcessingJob {} 장소 {}개 후보 재검토 시작", jobId, candidates.size());
        List<PlaceSelection> selections = placeSelectionRunner.run(candidates, jobId);

        List<GeocodingResult> result = new ArrayList<>(geocodedList);
        for (PlaceSelection selection : selections) {
            int index = selection.index();
            Integer selectedCandidateIndex = selection.selectedCandidateIndex();
            if (selectedCandidateIndex == null) {
                log.info("ProcessingJob {} 어느 후보도 맞지 않아 좌표 폐기: {}",
                        jobId, extractedList.get(index).name());
                result.set(index, GeocodingResult.empty());
            } else if (selectedCandidateIndex != 0) {
                GeocodingResult original = geocodedList.get(index);
                KakaoKeywordSearchResponse.Document chosen =
                        original.alternativeCandidates().get(selectedCandidateIndex - 1);
                log.info("ProcessingJob {} 카카오 1등 대신 다른 후보 채택: {} -> {}",
                        jobId, original.matchedName(), chosen.placeName());
                result.set(index, GeocodingResult.fromDocument(chosen));
            }
        }
        return result;
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
