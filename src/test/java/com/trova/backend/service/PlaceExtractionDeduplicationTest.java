package com.trova.backend.service;

import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.pipeline.PipelineOutput;
import com.trova.backend.pipeline.PipelineRunner;
import com.trova.backend.pipeline.PipelineStageMetrics;
import com.trova.backend.pipeline.PlaceSelectionRunner;
import com.trova.backend.pipeline.PlaceVerificationRunner;
import com.trova.backend.queue.QueuePath;
import com.trova.backend.queue.QueueWaitMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceExtractionDeduplicationTest {
    private final ProcessingJobLifecycleService lifecycle = mock(ProcessingJobLifecycleService.class);
    private final PipelineRunner pipeline = mock(PipelineRunner.class);
    private final com.trova.backend.geocoding.KakaoGeocodingService geocoding =
            mock(com.trova.backend.geocoding.KakaoGeocodingService.class);
    private final AutoDraftService drafts = mock(AutoDraftService.class);
    private final QueueWaitMetrics waitMetrics = mock(QueueWaitMetrics.class);

    private PlaceExtractionService service() {
        return new PlaceExtractionService(lifecycle, pipeline, geocoding, mock(PlaceSelectionRunner.class),
                mock(PlaceVerificationRunner.class), new FoundPlaceNameStore(), drafts, Runnable::run,
                new PipelineStageMetrics(new SimpleMeterRegistry()), waitMetrics);
    }

    private void dispatch(PlaceExtractionService service, QueuePath path) {
        if (path == QueuePath.ASYNC) {
            service.process(42L);
            return;
        }
        com.trova.backend.entity.ProcessingJob job = mock(com.trova.backend.entity.ProcessingJob.class);
        when(job.getStatus()).thenReturn(com.trova.backend.entity.JobStatus.PROCESSING);
        com.trova.backend.repository.ProcessingJobRepository repository = mock(com.trova.backend.repository.ProcessingJobRepository.class);
        when(repository.findById(42L)).thenReturn(java.util.Optional.of(job));
        new com.trova.backend.queue.ShareJobListener(repository, service).onMessage("42");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(QueuePath.class)
    void 늦은_경로는_선행_완료까지_기다리고_분석과_저장을_반복하지_않는다(QueuePath firstPath) throws Exception {
        QueuePath latePath = firstPath == QueuePath.ASYNC ? QueuePath.KAFKA : QueuePath.ASYNC;
        PlaceExtractionService service = service();
        CountDownLatch inPipeline = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lateStarted = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        when(lifecycle.startAnalysis(42L)).thenAnswer(inv -> done.get() ? null : "https://youtu.be/x");
        when(geocoding.searchCandidates(any(), any(), any(), eq(42L))).thenReturn(GeocodingResult.empty());
        when(geocoding.resolveRegionFallback(any(), any(), any(), eq(42L))).thenReturn(GeocodingResult.empty());
        ExtractedPlace place = new ExtractedPlace("가야랜드", "김해", "attraction", 0.9, null, null, List.of("가야랜드"));
        when(pipeline.run(eq("https://youtu.be/x"), eq(42L), any())).thenAnswer(inv -> {
            inPipeline.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new PipelineOutput("김해", List.of(place));
        });
        doAnswer(inv -> { done.set(true); return true; }).when(lifecycle).completeAnalysis(eq(42L), any(), any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> dispatch(service, firstPath));
            assertThat(inPipeline.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> late = workers.submit(() -> {
                lateStarted.countDown();
                dispatch(service, latePath);
            });
            assertThat(lateStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // Kafka 쪽은 먼저 완료로 돌아가지 않는다. 리스너의 오프셋 확정도 기다리게 한다.
            assertThatThrownBy(() -> late.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            late.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
        verify(pipeline, times(1)).run(any(), eq(42L), any());
        verify(lifecycle, times(1)).completeAnalysis(eq(42L), any(), any());
        verify(drafts, times(1)).startFor(42L);
        verify(waitMetrics, times(1)).record(42L, firstPath);
    }

    @Test
    void 이전_프로세스의_진행상태는_새_서비스에서_다시_처리할_수_있다() {
        // DB의 PROCESSING 복구 허용은 lifecycle 통합 테스트로 별도 확인한다.
        when(lifecycle.startAnalysis(42L)).thenReturn("https://youtu.be/x");
        when(pipeline.run(eq("https://youtu.be/x"), eq(42L), any())).thenReturn(new PipelineOutput("중단 후 재수신", List.of()));
        service().processNow(42L, QueuePath.KAFKA);
        verify(pipeline).run(any(), eq(42L), any());
    }

    @Test
    void 완료나_삭제로_시작을_거절하면_외부호출과_저장을_하지_않는다() {
        when(lifecycle.startAnalysis(42L)).thenReturn(null);
        service().processNow(42L, QueuePath.KAFKA);
        verify(pipeline, never()).run(any(), any(), any());
        verify(lifecycle, never()).completeAnalysis(any(), any(), any());
        verify(waitMetrics, never()).record(any(), any());
    }

    @Test
    void 서로_다른_작업은_한_작업의_외부호출을_기다리지_않는다() throws Exception {
        PlaceExtractionService service = service();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(lifecycle.startAnalysis(42L)).thenReturn("https://youtu.be/x");
        when(lifecycle.startAnalysis(43L)).thenReturn("https://youtu.be/y");
        when(pipeline.run(eq("https://youtu.be/x"), eq(42L), any())).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new PipelineOutput("첫 작업", List.of());
        });
        when(pipeline.run(eq("https://youtu.be/y"), eq(43L), any())).thenReturn(new PipelineOutput("다른 작업", List.of()));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> service.processNow(42L, QueuePath.ASYNC));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> service.processNow(43L, QueuePath.KAFKA)).get(5, TimeUnit.SECONDS);
            assertThat(first.isDone()).isFalse();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void 선행_실패_후_늦은_메시지는_외부호출을_반복하지_않는다() {
        PlaceExtractionService service = service();
        when(lifecycle.startAnalysis(42L)).thenReturn("https://youtu.be/x").thenReturn(null);
        when(pipeline.run(eq("https://youtu.be/x"), eq(42L), any())).thenThrow(new IllegalStateException("controlled failure"));
        dispatch(service, QueuePath.ASYNC);
        dispatch(service, QueuePath.KAFKA);
        verify(pipeline, times(1)).run(any(), eq(42L), any());
        verify(lifecycle, times(1)).markAnalysisFailed(42L, "controlled failure");
        verify(lifecycle, never()).completeAnalysis(any(), any(), any());
        verify(drafts, never()).startFor(any());
    }

    @Test
    void 시작상태_조회실패는_기존_결과를_실패로_덮어쓰지_않는다() throws Exception {
        PlaceExtractionService service = service();
        TransientDataAccessResourceException unavailable = new TransientDataAccessResourceException("DB unavailable");
        when(lifecycle.startAnalysis(42L)).thenThrow(unavailable).thenReturn(null);
        assertThatThrownBy(() -> dispatch(service, QueuePath.KAFKA)).isSameAs(unavailable);
        // 다른 스레드가 이어서 잠금을 얻고 DB의 완료 결과를 다시 확인할 수 있어야 한다.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> dispatch(service, QueuePath.KAFKA)).get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        verify(lifecycle, times(2)).startAnalysis(42L);
        verify(lifecycle, never()).markAnalysisFailed(any(), any());
        verify(pipeline, never()).run(any(), any(), any());
        verify(lifecycle, never()).completeAnalysis(any(), any(), any());
        verify(drafts, never()).startFor(any());
    }
}
