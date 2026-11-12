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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import com.trova.backend.pipeline.PipelineStageMetrics;
import com.trova.backend.queue.QueueWaitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceExtractionServiceTest {

    private static ProcessingJobLifecycleService.AnalysisStart started(String url) {
        return new ProcessingJobLifecycleService.AnalysisStart(url, java.time.LocalDateTime.of(2026, 11, 1, 0, 0));
    }

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
    @Mock
    private AutoDraftService autoDraftService;
    @Spy
    private FoundPlaceNameStore foundPlaceNameStore = new FoundPlaceNameStore();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private PlaceExtractionService placeExtractionService;

    @BeforeEach
    void setUp() {
        // 기본은 호출한 스레드에서 바로 실행 — 동시 실행 자체를 보는 테스트만 실제 스레드 풀로 따로 만든다.
        placeExtractionService = serviceWith(Runnable::run);
        org.mockito.Mockito.lenient().when(lifecycleService.completeAnalysis(any(), any(), any())).thenReturn(true);
    }

    private PlaceExtractionService serviceWith(Executor geocodingTaskExecutor) {
        return new PlaceExtractionService(lifecycleService, pipelineRunner, kakaoGeocodingService,
                placeSelectionRunner, placeVerificationRunner, foundPlaceNameStore, autoDraftService, geocodingTaskExecutor,
                new PipelineStageMetrics(registry), mock(QueueWaitMetrics.class));
    }

    private static GeocodingResult at(String name, double lat, double lng) {
        return new GeocodingResult(lat, lng, name, null, null, null, null, null, null);
    }

    @Test
    void 영상_중심에서_먼_체인점은_중심_근처_같은_상호_지점으로_바꾼다() {
        // 홍대 영상의 이삭토스트가 5.7km 떨어진 명동성당점으로 잡혔다(안드로이드 사용성 테스트 2026-11-12, #414).
        GeocodingResult hongdae1 = at("트릭아이뮤지엄", 37.5537, 126.9221);
        GeocodingResult hongdae2 = at("코코넛박스", 37.5550, 126.9230);
        GeocodingResult hongdae3 = at("나이키 홍대", 37.5560, 126.9240);
        GeocodingResult myeongdong = at("이삭토스트 서울명동성당점", 37.5634, 126.9869);
        GeocodingResult hongdaeIsaac = at("이삭토스트 홍대점", 37.5545, 126.9225);
        org.mockito.Mockito.when(kakaoGeocodingService.findBranchNear(
                        eq("이삭토스트 서울명동성당점"), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyInt(), anyLong()))
                .thenReturn(java.util.Optional.of(hongdaeIsaac));

        List<GeocodingResult> result = placeExtractionService.moveOutlierBranches(
                1L, List.of(hongdae1, myeongdong, hongdae2, hongdae3));

        org.assertj.core.api.Assertions.assertThat(result).containsExactly(hongdae1, hongdaeIsaac, hongdae2, hongdae3);
    }

    @Test
    void 장소가_3곳보다_적으면_중심을_정하지_않고_그대로_둔다() {
        List<GeocodingResult> two = List.of(at("A", 37.55, 126.92), at("B", 37.56, 126.99));

        org.assertj.core.api.Assertions.assertThat(placeExtractionService.moveOutlierBranches(1L, two)).isEqualTo(two);
        org.mockito.Mockito.verifyNoInteractions(kakaoGeocodingService);
    }

    @Test
    void 정상_처리시_단계가_순서대로_기록된다() {
        Long jobId = 1L;
        ExtractedPlace extracted = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/x"));
        when(pipelineRunner.run(eq("https://youtu.be/x"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("부산 여행", List.of(extracted)));
        when(kakaoGeocodingService.searchCandidates(any(), any(), any(), anyLong()))
                .thenReturn(GeocodingResult.coordinatesOnly(35.16, 129.16));
        // 후보가 여러 개도 아니고(선택 대상 없음), 확신도 0.95라 검증 대상도 아니므로
        // selectAmongAlternatives/verifyUncertainMatches는 둘 다 Gemini 호출 없이 스킵된다 —
        // 그래도 SELECTING/VERIFYING 단계 기록 자체는 스킵되지 않는다.

        placeExtractionService.process(jobId);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).startAnalysisWithMetadata(jobId);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.EXTRACTING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.GEOCODING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.SELECTING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.VERIFYING);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.SAVING);
        order.verify(lifecycleService).completeAnalysis(eq(jobId), any(), any());
        verify(autoDraftService).startFor(jobId);
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "success").timer().count()).isEqualTo(1);
        for (String stage : List.of("preparing", "extracting", "geocoding", "selecting", "verifying", "saving")) {
            assertThat(registry.get("trova.analysis.stage").tags("stage", stage, "outcome", "success").timer().count()).isEqualTo(1);
        }
    }

    @Test
    void 파이프라인_실패시_단계_기록_없이_실패_처리된다() {
        Long jobId = 2L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/y"));
        when(pipelineRunner.run(eq("https://youtu.be/y"), eq(jobId), any())).thenThrow(new RuntimeException("파이프라인 오류"));

        placeExtractionService.process(jobId);

        InOrder order = inOrder(lifecycleService);
        order.verify(lifecycleService).startAnalysisWithMetadata(jobId);
        order.verify(lifecycleService).updateStage(jobId, ProcessingStage.EXTRACTING);
        order.verify(lifecycleService).markAnalysisFailed(jobId, "파이프라인 오류");
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("trova.analysis.stage").tags("stage", "extracting", "outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    void 분석_중_받은_제목은_바로_저장하고_찾은_이름은_끝날_때까지만_보관한다() {
        // 앱 분석 화면이 끝나기 전에 제목·찾은 장소를 보여주기 위한 중간 결과(#51).
        Long jobId = 3L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/z"));
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
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/empty"));
        when(pipelineRunner.run(eq("https://youtu.be/empty"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("그냥 일상 브이로그", List.of()));

        placeExtractionService.process(jobId);

        verify(lifecycleService).setTitle(jobId, "그냥 일상 브이로그");
        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.NO_PLACES_MESSAGE);
        verify(lifecycleService, never()).completeAnalysis(any(), any(), any());
        verify(kakaoGeocodingService, never()).searchCandidates(any(), any(), any(), anyLong());
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "no_places").timer().count()).isEqualTo(1);
    }

    @Test
    void Gemini_하루_한도_소진으로_실패하면_AI_한도_실패로_기록한다() {
        // 일반 실패로 두면 앱이 "다시 시도"를 보여주는데, 한도가 초기화되기 전엔 다시 해도 실패한다(#63).
        Long jobId = 5L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/q"));
        when(pipelineRunner.run(eq("https://youtu.be/q"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): GEMINI_DAILY_QUOTA_EXCEEDED Gemini 하루 한도 소진: {...}"));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.AI_QUOTA_MESSAGE);
    }

    @Test
    void 인스타그램_속도_제한으로_실패하면_정해진_문구로_기록한다() {
        Long jobId = 6L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://www.instagram.com/reel/x/"));
        when(pipelineRunner.run(eq("https://www.instagram.com/reel/x/"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): SOURCE_RATE_LIMITED 인스타그램이 요청을 막음(429): ..."));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE);
    }

    @Test
    void 게시물에서_영상도_사진도_받지_못하면_정해진_문구로_기록한다() {
        // #196: 다시 해도 같은 실패라 앱이 [다시 시도] 대신 이유를 보여준다.
        Long jobId = 7L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://www.instagram.com/p/x/"));
        when(pipelineRunner.run(eq("https://www.instagram.com/p/x/"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): NO_MEDIA 게시물에서 영상도 사진도 받지 못했습니다"));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.NO_MEDIA_MESSAGE);
    }

    @Test
    void 비공개나_삭제된_게시물이면_정해진_문구로_기록한다() {
        // #257: 비공개·삭제·로그인 필요는 다시 해도 같아 앱이 [다시 시도] 대신 이유를 보여준다.
        Long jobId = 8L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://www.instagram.com/reel/x/"));
        when(pipelineRunner.run(eq("https://www.instagram.com/reel/x/"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): SOURCE_UNAVAILABLE 볼 수 없는 게시물(로그인 필요): ERROR: login required"));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.SOURCE_UNAVAILABLE_MESSAGE);
    }

    @Test
    void 장소를_소개하지_않는_콘텐츠면_정해진_문구로_기록한다() {
        // #206: 제품 광고·요리·일상 — 다시 해도 같아 앱이 [다시 시도] 대신 이유를 보여준다.
        Long jobId = 8L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://www.instagram.com/reel/ad/"));
        when(pipelineRunner.run(eq("https://www.instagram.com/reel/ad/"), eq(jobId), any())).thenThrow(new PipelineException(
                "파이프라인 실행 실패(exit=1): NOT_PLACE_CONTENT 장소를 소개하는 콘텐츠가 아님 (후보 1개)"));

        placeExtractionService.process(jobId);

        verify(lifecycleService).markAnalysisFailed(jobId, ProcessingJob.NOT_PLACE_CONTENT_MESSAGE);
    }

    @Test
    void 장소별_후보_검색은_동시에_보낸다() throws Exception {
        // 순서대로 보내면 첫 검색이 둘째 검색을 기다리다 시간 초과로 끝난다 — 둘 다 동시에 들어와야 통과(#7).
        Long jobId = 7L;
        ExtractedPlace a = new ExtractedPlace("수로왕릉", "김해", "attraction", 0.95, null, null, List.of("수로왕릉"));
        ExtractedPlace b = new ExtractedPlace("봉황동유적", "김해", "attraction", 0.95, null, null, List.of("봉황동유적"));
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/p"));
        when(pipelineRunner.run(eq("https://youtu.be/p"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("김해", List.of(a, b)));
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean(true);
        when(kakaoGeocodingService.searchCandidates(any(), any(), any(), anyLong())).thenAnswer(inv -> {
            bothStarted.countDown();
            if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                overlapped.set(false);
            }
            return GeocodingResult.coordinatesOnly(35.23, 128.87);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            serviceWith(pool).process(jobId);
        } finally {
            pool.shutdownNow();
        }

        assertThat(overlapped).isTrue();
        verify(kakaoGeocodingService, times(2)).searchCandidates(any(), any(), any(), anyLong());
    }

    @Test
    void 지역_폴백은_앞_장소의_좌표를_반영한_뒤_순서대로_한다() {
        // 지역 중심 좌표가 같은 영상의 다른 장소와 겹치지 않게 하려면, 폴백 때 앞 장소 좌표가 이미 반영돼 있어야 한다.
        Long jobId = 8L;
        ExtractedPlace found = new ExtractedPlace("전주한옥마을", "전주", "attraction", 0.95, null, null, List.of("전주한옥마을"));
        ExtractedPlace missing = new ExtractedPlace("없는가게", "전주", "restaurant", 0.95, null, null, List.of("없는가게"));
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/j"));
        when(pipelineRunner.run(eq("https://youtu.be/j"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("전주", List.of(found, missing)));
        GeocodingResult hanok = GeocodingResult.coordinatesOnly(35.81, 127.15);
        when(kakaoGeocodingService.searchCandidates(eq(List.of("전주한옥마을")), any(), any(), anyLong())).thenReturn(hanok);
        when(kakaoGeocodingService.searchCandidates(eq(List.of("없는가게")), any(), any(), anyLong()))
                .thenReturn(GeocodingResult.empty());
        Set<String> keysAtFallback = new HashSet<>();
        when(kakaoGeocodingService.resolveRegionFallback(eq(List.of("없는가게")), eq("전주"), any(), eq(jobId)))
                .thenAnswer(inv -> {
                    keysAtFallback.addAll(inv.<Set<String>>getArgument(2));
                    return GeocodingResult.empty();
                });

        placeExtractionService.process(jobId);

        assertThat(keysAtFallback).containsExactly(hanok.coordinateKey());
        verify(kakaoGeocodingService, never()).resolveRegionFallback(eq(List.of("전주한옥마을")), any(), any(), anyLong());
    }

    @Test
    void 자동_초안이_실패해도_분석은_완료로_남는다() {
        Long jobId = 9L;
        ExtractedPlace extracted = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/x"));
        when(pipelineRunner.run(eq("https://youtu.be/x"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("부산 여행", List.of(extracted)));
        when(kakaoGeocodingService.searchCandidates(any(), any(), any(), anyLong()))
                .thenReturn(GeocodingResult.coordinatesOnly(35.16, 129.16));
        doThrow(new RuntimeException("x")).when(autoDraftService).startFor(any());

        placeExtractionService.process(jobId);

        verify(lifecycleService).completeAnalysis(eq(jobId), any(), any());
        verify(lifecycleService, never()).markAnalysisFailed(any(), any());
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void 완료_직전_작업이_종료됐으면_자동_초안을_시작하지_않는다() {
        Long jobId = 999L;
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/stopped"));
        ExtractedPlace place = new ExtractedPlace("해운대", "부산", "attraction", 0.9, null, null, List.of("해운대"));
        when(pipelineRunner.run(eq("https://youtu.be/stopped"), eq(jobId), any()))
                .thenReturn(new PipelineOutput("부산", List.of(place)));
        when(kakaoGeocodingService.searchCandidates(any(), any(), any(), eq(jobId)))
                .thenReturn(GeocodingResult.coordinatesOnly(35.1, 129.1));
        when(lifecycleService.completeAnalysis(eq(jobId), any(), any())).thenReturn(false);
        placeExtractionService.process(jobId);
        verify(autoDraftService, never()).startFor(jobId);
        verify(lifecycleService, never()).markAnalysisFailed(any(), any());
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "discarded").timer().count()).isEqualTo(1);
    }
    @Test
    void 저장_실패는_저장_단계와_실행_실패로_한번씩_기록한다() {
        Long jobId = 1001L;
        ExtractedPlace place = new ExtractedPlace("해운대", "부산", "attraction", 0.95, null, null, List.of("해운대"));
        when(lifecycleService.startAnalysisWithMetadata(jobId)).thenReturn(started("https://youtu.be/x"));
        when(pipelineRunner.run(any(), eq(jobId), any())).thenReturn(new PipelineOutput("부산", List.of(place)));
        when(kakaoGeocodingService.searchCandidates(any(), any(), any(), eq(jobId)))
                .thenReturn(GeocodingResult.coordinatesOnly(35.1, 129.1));
        when(lifecycleService.completeAnalysis(eq(jobId), any(), any())).thenThrow(new RuntimeException("DB unavailable"));

        placeExtractionService.process(jobId);

        assertThat(registry.get("trova.analysis.stage").tags("stage", "saving", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("trova.analysis.execution").tag("outcome", "success").timer().count()).isZero();
        verify(lifecycleService).markAnalysisFailed(jobId, "DB unavailable");
        verify(autoDraftService, never()).startFor(jobId);
    }

    @Test
    void 단계_상태_갱신_실패도_해당_단계에_포함한다() {
        when(lifecycleService.startAnalysisWithMetadata(1002L)).thenReturn(started("https://youtu.be/x"));
        doThrow(new RuntimeException("update failed")).when(lifecycleService).updateStage(1002L, ProcessingStage.EXTRACTING);

        placeExtractionService.process(1002L);

        assertThat(registry.get("trova.analysis.stage").tags("stage", "extracting", "outcome", "failure").timer().count()).isEqualTo(1);
        verify(pipelineRunner, never()).run(any(), any(), any());
    }

    @Test
    void 이미_완료된_중복_작업은_새_실행으로_세지_않는다() {
        when(lifecycleService.startAnalysisWithMetadata(1003L)).thenReturn(null);

        placeExtractionService.process(1003L);

        assertThat(registry.find("trova.analysis.execution").timers()).allSatisfy(timer -> assertThat(timer.count()).isZero());
    }

}
