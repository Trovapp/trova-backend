package com.trova.backend.service;

import com.trova.backend.entity.ProcessingStage;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.geocoding.KakaoGeocodingService;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.pipeline.PipelineException;
import com.trova.backend.pipeline.PipelineProgress;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PlaceSelectionRunner;
import com.trova.backend.pipeline.PlaceVerificationRunner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @Spy
    private FoundPlaceNameStore foundPlaceNameStore = new FoundPlaceNameStore();

    @InjectMocks
    private PlaceExtractionService placeExtractionService;

    @Test
    void 정상_처리시_단계가_순서대로_기록된다() {
        Long jobId = 1L;
        ExtractedPlace extracted = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/x");
        when(pipelineRunner.run(eq("https://youtu.be/x"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("부산 여행", List.of(extracted)));
        when(kakaoGeocodingService.geocode(any(), any(), any(), any(Set.class), anyLong()))
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
        when(pipelineRunner.run(eq("https://youtu.be/y"), eq(jobId), any())).thenThrow(new RuntimeException("파이프라인 오류"));

        placeExtractionService.process(jobId);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).markProcessing(jobId);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.EXTRACTING);
        order.verify(lifecycleService).markFailed(jobId, "파이프라인 오류");
    }

    @Test
    void 분석_중_받은_제목은_바로_저장하고_찾은_이름은_끝날_때까지만_보관한다() {
        // 앱 분석 화면이 끝나기 전에 제목·찾은 장소를 보여주기 위한 중간 결과(#51).
        Long jobId = 3L;
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/z");
        List<List<String>> namesSeenDuringRun = new java.util.ArrayList<>();
        when(pipelineRunner.run(eq("https://youtu.be/z"), eq(jobId), any())).thenAnswer(inv -> {
            java.util.function.Consumer<PipelineProgress> onProgress = inv.getArgument(2);
            onProgress.accept(new PipelineProgress("김해 당일치기", null));
            onProgress.accept(new PipelineProgress(null, List.of("가야랜드", "수로왕릉")));
            namesSeenDuringRun.add(foundPlaceNameStore.get(jobId));
            return new PipelineOutput("김해 당일치기", List.of());
        });

        placeExtractionService.process(jobId);

        verify(lifecycleService, times(2)).setTitle(jobId, "김해 당일치기");
        assertThat(namesSeenDuringRun).containsExactly(List.of("가야랜드", "수로왕릉"));
        // 작업이 끝나면(성공·실패 모두) 메모리에서 지운다.
        assertThat(foundPlaceNameStore.get(jobId)).isEmpty();
    }

    @Test
    void 장소를_하나도_못_찾으면_완료가_아니라_장소_없음으로_실패_처리한다() {
        // DONE으로 끝내면 앱은 결과 화면에서 "해당 영상을 찾을 수 없어요"를 띄우고, 영상은 어디에도 남지 않았다(#55).
        Long jobId = 4L;
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/empty");
        when(pipelineRunner.run(eq("https://youtu.be/empty"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("그냥 일상 브이로그", List.of()));

        placeExtractionService.process(jobId);

        verify(lifecycleService).setTitle(jobId, "그냥 일상 브이로그");
        verify(lifecycleService).markFailed(jobId, ProcessingJob.NO_PLACES_MESSAGE);
        verify(lifecycleService, never()).markDone(any());
        verify(lifecycleService, never()).savePlace(any(), any(), any());
        verify(kakaoGeocodingService, never()).geocode(any(), any(), any(), any(Set.class), anyLong());
    }

    @Test
    void Gemini_하루_한도_소진으로_실패하면_AI_한도_실패로_기록한다() {
        // 일반 실패로 두면 앱이 "다시 시도"를 보여주는데, 한도가 초기화되기 전엔 다시 해도 실패한다(#63).
        Long jobId = 5L;
        when(lifecycleService.markProcessing(jobId)).thenReturn("https://youtu.be/q");
        when(pipelineRunner.run(eq("https://youtu.be/q"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): GEMINI_DAILY_QUOTA_EXCEEDED Gemini 하루 한도 소진: {...}"));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markFailed(jobId, ProcessingJob.AI_QUOTA_MESSAGE);
    }
}
