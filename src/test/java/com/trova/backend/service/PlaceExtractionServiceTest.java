package com.trova.backend.service;

import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PlaceSelectionRunner;
import com.trova.backend.pipeline.PlaceVerificationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceExtractionServiceTest {

    @Mock
    private ProcessingJobLifecycleService lifecycleService;
    @Mock
    private PipelineRunner pipelineRunner;
    @Mock
    private KakaoGeocodingService kakaoGeocodingService;
    @Mock
    private PlaceSelectionRunner placeSelectionRunner;
    @Mock
    private PlaceVerificationRunner placeVerificationRunner;

    private PlaceExtractionService placeExtractionService;

    @BeforeEach
    void setUp() {
        // 지오코딩 병렬 팬아웃용 executor — 테스트에서는 스레드풀 대신 호출 스레드에서
        // 즉시 실행시켜(Runnable::run) 실제 쓰레딩 없이 순수 로직만 검증한다.
        placeExtractionService = new PlaceExtractionService(
                lifecycleService, pipelineRunner, kakaoGeocodingService,
                placeSelectionRunner, placeVerificationRunner, Runnable::run);
    }

    @Test
    void 정상_처리시_단계가_순서대로_기록된다() {
        Long jobId = 1L;
        ExtractedPlace extracted = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/x");
        when(pipelineRunner.run("https://youtu.be/x", jobId))
                .thenReturn(new PipelineOutput("부산 여행", List.of(extracted)));
        when(kakaoGeocodingService.searchCandidates(any(), any(), anyLong()))
                .thenReturn(GeocodingResult.coordinatesOnly(35.16, 129.16));
        // 후보가 여러 개도 아니고(선택 대상 없음), 확신도 0.95라 검증 대상도 아니므로
        // selectAmongAlternatives/verifyUncertainMatches는 둘 다 Gemini 호출 없이 스킵된다 —
        // 그래도 SELECTING/VERIFYING 단계 기록 자체는 스킵되지 않는다.

        placeExtractionService.process(jobId);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).markProcessing(jobId);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.EXTRACTING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.GEOCODING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.SELECTING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.VERIFYING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.SAVING);
        order.verify(lifecycleService).savePlace(any(), any(), any());
        order.verify(lifecycleService).markDone(jobId);
    }

    @Test
    void 파이프라인_실패시_단계_기록_없이_실패_처리된다() {
        Long jobId = 2L;
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/y");
        when(pipelineRunner.run("https://youtu.be/y", jobId)).thenThrow(new RuntimeException("파이프라인 오류"));

        placeExtractionService.process(jobId);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).markProcessing(jobId);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.EXTRACTING);
        order.verify(lifecycleService).markFailed(jobId, "파이프라인 오류");
    }
}
