package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.queue.ReplanJobQueue;
import com.trova.backend.queue.ShareJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StuckJobRequeueServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 12, 0);

    private final ProcessingJobRepository processingJobRepository = mock(ProcessingJobRepository.class);
    private final TripDraftRepository tripDraftRepository = mock(TripDraftRepository.class);
    private final TripReplanJobRepository tripReplanJobRepository = mock(TripReplanJobRepository.class);
    private final ShareJobQueue shareJobQueue = mock(ShareJobQueue.class);
    private final DraftJobQueue draftJobQueue = mock(DraftJobQueue.class);
    private final ReplanJobQueue replanJobQueue = mock(ReplanJobQueue.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private StuckJobRequeueService service;

    @BeforeEach
    void setUp() {
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(tripDraftRepository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(tripReplanJobRepository.findIdsByStatusAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
        service = new StuckJobRequeueService(processingJobRepository, tripDraftRepository, tripReplanJobRepository,
                shareJobQueue, draftJobQueue, replanJobQueue, meterRegistry);
    }

    private double requeued(String queue) {
        return meterRegistry.get("trova.queue.requeued").tag("queue", queue).counter().count();
    }

    @Test
    void 멈춘_작업을_각_대기열로_다시_보내고_지표를_올린다() {
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(1L));
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PROCESSING), any(), any())).thenReturn(List.of(2L));
        when(tripDraftRepository.findIdsByStatusAndUpdatedAtBetween(eq(TripDraftStatus.PENDING), any(), any())).thenReturn(List.of(3L));
        when(tripReplanJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PROCESSING), any(), any())).thenReturn(List.of(4L));

        int sent = service.requeueStuckJobs(NOW);

        assertThat(sent).isEqualTo(4);
        verify(shareJobQueue).enqueue(1L);
        verify(shareJobQueue).enqueue(2L);
        verify(draftJobQueue).enqueue(3L);
        verify(replanJobQueue).enqueue(4L);
        assertThat(requeued("share")).isEqualTo(2.0);
        assertThat(requeued("draft")).isEqualTo(1.0);
        assertThat(requeued("replan")).isEqualTo(1.0);
    }

    @Test
    void 대기는_3분_처리중은_8분_넘은_것만_정리기_15분_전까지_고른다() {
        service.requeueStuckJobs(NOW);

        LocalDateTime giveUp = NOW.minusMinutes(15);
        verify(processingJobRepository).findIdsByStatusAndUpdatedAtBetween(JobStatus.PENDING, giveUp, NOW.minusMinutes(3));
        verify(processingJobRepository).findIdsByStatusAndUpdatedAtBetween(JobStatus.PROCESSING, giveUp, NOW.minusMinutes(8));
        verify(tripDraftRepository).findIdsByStatusAndUpdatedAtBetween(TripDraftStatus.PENDING, giveUp, NOW.minusMinutes(3));
        verify(tripDraftRepository).findIdsByStatusAndUpdatedAtBetween(TripDraftStatus.PROCESSING, giveUp, NOW.minusMinutes(8));
        verify(tripReplanJobRepository).findIdsByStatusAndUpdatedAtBetween(JobStatus.PENDING, giveUp, NOW.minusMinutes(3));
        verify(tripReplanJobRepository).findIdsByStatusAndUpdatedAtBetween(JobStatus.PROCESSING, giveUp, NOW.minusMinutes(8));
    }

    @Test
    void 같은_작업은_한_번만_다시_보낸다() {
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(1L));

        service.requeueStuckJobs(NOW);
        int second = service.requeueStuckJobs(NOW.plusMinutes(1));

        assertThat(second).isZero();
        verify(shareJobQueue, times(1)).enqueue(1L);
    }

    @Test
    void 다시_보낸_기록은_15분이_지나면_지운다() {
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(1L));
        service.requeueStuckJobs(NOW);

        // 16분 뒤에도 같은 id가 다시 고르게 되면(정리기 기준을 넘긴 뒤라 실제로는 고르지 않는다) 기록은 이미 지워져 있다.
        int later = service.requeueStuckJobs(NOW.plusMinutes(16));

        assertThat(later).isEqualTo(1);
        verify(shareJobQueue, times(2)).enqueue(1L);
    }

    @Test
    void 한_건을_보내지_못해도_나머지는_보낸다() {
        when(processingJobRepository.findIdsByStatusAndUpdatedAtBetween(eq(JobStatus.PENDING), any(), any())).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("broker")).when(shareJobQueue).enqueue(1L);

        int sent = service.requeueStuckJobs(NOW);

        assertThat(sent).isEqualTo(1);
        verify(shareJobQueue).enqueue(2L);
        assertThat(requeued("share")).isEqualTo(1.0);
    }

    @Test
    void 멈춘_작업이_없으면_아무것도_보내지_않는다() {
        assertThat(service.requeueStuckJobs(NOW)).isZero();
        verify(shareJobQueue, never()).enqueue(any());
        verify(draftJobQueue, never()).enqueue(any());
        verify(replanJobQueue, never()).enqueue(any());
        assertThat(requeued("share")).isZero();
    }
}
