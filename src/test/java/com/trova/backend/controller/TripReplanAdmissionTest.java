package com.trova.backend.controller;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.service.CurrentUserService;
import com.trova.backend.service.DailyQuotaService;
import com.trova.backend.service.TripReplanJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripReplanAdmissionTest {
    private final User user = mock(User.class);
    private final Trip trip = mock(Trip.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripReplanJobRepository jobs = mock(TripReplanJobRepository.class);
    private final TripReplanJobService worker = mock(TripReplanJobService.class);
    private final DailyQuotaService quota = new DailyQuotaService(mock(ProcessingJobRepository.class), 20, 1);
    private final TripReplanJob saved = mock(TripReplanJob.class);
    private TripReplanController controller;

    @BeforeEach
    void 준비() {
        when(user.getId()).thenReturn(7L);
        when(trip.getId()).thenReturn(9L);
        when(trip.getUser()).thenReturn(user);
        when(trips.findById(9L)).thenReturn(Optional.of(trip));
        when(saved.getId()).thenReturn(1L);
        when(jobs.save(any())).thenReturn(saved);
        CurrentUserService users = mock(CurrentUserService.class);
        when(users.resolve(any())).thenReturn(user);
        controller = new TripReplanController(users, trips, jobs, worker, quota);
    }

    private Long submit() {
        return ((TripReplanController.CreateReplanJobResponse) controller.replan(mock(Authentication.class), 9L,
                new TripReplanController.TripReplanRequest(true, false)).getBody()).jobId();
    }

    @Test
    void 한도_초과는_저장이나_작업자_호출_전에_거절한다() {
        quota.consumePlaceCall(user);
        assertThatThrownBy(this::submit).isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        verify(jobs, never()).save(any());
        verify(worker, never()).process(any());
    }

    @Test
    void 한도를_소진했어도_기존_작업은_차감없이_재사용한다() {
        quota.consumePlaceCall(user);
        when(jobs.findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
                any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(saved));
        assertThat(submit()).isEqualTo(1L);
        verify(jobs, never()).save(any());
        verify(worker, never()).process(any());
    }

    @Test
    @Timeout(10)
    void 같은_여행의_동시_접수는_작업과_한도를_한번만_사용한다() throws Exception {
        AtomicReference<TripReplanJob> stored = new AtomicReference<>();
        when(jobs.findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
                any(), any(), anyBoolean(), anyBoolean(), any(), any()))
                .thenAnswer(call -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(jobs.save(any())).thenAnswer(call -> { stored.set(saved); return saved; });
        CountDownLatch firstEnqueue = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(call -> { firstEnqueue.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(worker).process(1L);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Long> first = pool.submit(this::submit);
            assertThat(firstEnqueue.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Long> second = pool.submit(() -> { secondStarted.countDown(); return submit(); });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                Thread.sleep(100);
                assertThat(second.isDone()).isFalse();
                verify(jobs, times(1)).findByUserAndTripAndIndoorOnlyAndAllPlacesAndStatusInAndUpdatedAtAfter(
                        any(), any(), anyBoolean(), anyBoolean(), any(), any());
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        verify(jobs, times(1)).save(any());
        verify(worker, times(1)).process(1L);
        assertThatThrownBy(() -> quota.consumePlaceCall(user)).isInstanceOf(DailyQuotaService.QuotaExceededException.class);
    }

    @Test
    void 잘못된_조건과_소유권은_한도를_차감하지_않는다() {
        assertThat(controller.replan(mock(Authentication.class), 9L,
                new TripReplanController.TripReplanRequest(false, false)).getStatusCode().value()).isEqualTo(400);
        when(trips.findById(9L)).thenReturn(Optional.empty());
        assertThat(controller.replan(mock(Authentication.class), 9L,
                new TripReplanController.TripReplanRequest(true, false)).getStatusCode().value()).isEqualTo(404);
        when(trips.findById(9L)).thenReturn(Optional.of(trip));
        assertThat(submit()).isEqualTo(1L);
    }

    @Test
    void 저장_실패도_시도는_차감하며_작업자는_부르지_않고_잠금을_해제한다() {
        when(jobs.save(any())).thenThrow(new IllegalStateException("injected save failure"));
        assertThatThrownBy(this::submit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::submit).isInstanceOf(DailyQuotaService.QuotaExceededException.class);
        verify(worker, never()).process(any());
        verify(jobs, times(1)).save(any());
    }

    @Test
    void 큐_거절은_행을_삭제하고_시도_한도는_유지한다() {
        doThrow(new TaskRejectedException("busy")).when(worker).process(1L);
        assertThatThrownBy(this::submit).isInstanceOf(TaskRejectedException.class);
        verify(jobs).delete(saved);
        assertThatThrownBy(this::submit).isInstanceOf(DailyQuotaService.QuotaExceededException.class);
    }
}
