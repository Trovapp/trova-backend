package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 재시작되면 메모리 대기열(@Async)에 있던 작업이 사라져 DB에 PENDING/PROCESSING으로 영원히 남았다(#43).
 * 오래 갱신되지 않은 작업만 FAILED로 바꿔야 한다 — 로컬·배포 서버가 같은 DB를 쓰므로, 다른 서버가 지금
 * 처리 중인(최근 갱신된) 작업은 건드리면 안 된다.
 */
@SpringBootTest
class OrphanedJobRecoveryServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "orphaned-job-recovery-1";

    @Autowired private OrphanedJobRecoveryService recoveryService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripReplanJobRepository tripReplanJobRepository;
    @Autowired private TripDraftRepository tripDraftRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID).ifPresent(user -> {
            tripDraftRepository.deleteAll(tripDraftRepository.findAll().stream()
                    .filter(d -> d.getUser().getId().equals(user.getId())).toList());
            tripReplanJobRepository.deleteAll(tripReplanJobRepository.findAll().stream()
                    .filter(j -> j.getUser().getId().equals(user.getId())).toList());
            tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
            processingJobRepository.deleteAll(processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user));
            userRepository.delete(user);
        });
    }

    private User user() {
        return userRepository.save(new User("google", PROVIDER_USER_ID, "복구", null));
    }

    private ProcessingJob processingJob(User user, JobStatus status, int minutesSinceUpdate) {
        ProcessingJob job = new ProcessingJob(user, "https://youtu.be/orphan-" + System.nanoTime(), SourcePlatform.YOUTUBE);
        if (status == JobStatus.PROCESSING) job.markProcessing();
        if (status == JobStatus.DONE) job.markDone();
        ReflectionTestUtils.setField(job, "updatedAt", LocalDateTime.now().minusMinutes(minutesSinceUpdate));
        return processingJobRepository.save(job);
    }

    @Test
    void 오래_갱신되지_않은_처리_작업만_실패로_바꾼다() {
        User user = user();
        ProcessingJob stalePending = processingJob(user, JobStatus.PENDING, 20);
        ProcessingJob staleProcessing = processingJob(user, JobStatus.PROCESSING, 20);
        ProcessingJob recentProcessing = processingJob(user, JobStatus.PROCESSING, 5);
        ProcessingJob oldDone = processingJob(user, JobStatus.DONE, 60);

        recoveryService.failStaleJobs();

        assertThat(processingJobRepository.findById(stalePending.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        ProcessingJob failed = processingJobRepository.findById(staleProcessing.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(OrphanedJobRecoveryService.STALE_MESSAGE);
        assertThat(processingJobRepository.findById(recentProcessing.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(processingJobRepository.findById(oldDone.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void 오래_갱신되지_않은_일정_재구성_작업도_실패로_바꾼다() {
        User user = user();
        Trip trip = tripRepository.save(new Trip(user, "여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)));
        TripReplanJob stale = new TripReplanJob(user, trip, false);
        stale.markProcessing();
        ReflectionTestUtils.setField(stale, "updatedAt", LocalDateTime.now().minusMinutes(20));
        stale = tripReplanJobRepository.save(stale);
        TripReplanJob recent = tripReplanJobRepository.save(new TripReplanJob(user, trip, false));

        recoveryService.failStaleJobs();

        TripReplanJob failed = tripReplanJobRepository.findById(stale.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(OrphanedJobRecoveryService.STALE_MESSAGE);
        assertThat(tripReplanJobRepository.findById(recent.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void 오래_갱신되지_않은_일정_초안도_실패로_바꾸고_답을_기다리는_초안은_두다() {
        User user = user();
        TripDraft stale = new TripDraft(user, List.of(1L), "1박 2일");
        stale.markProcessing();
        ReflectionTestUtils.setField(stale, "updatedAt", LocalDateTime.now().minusMinutes(20));
        stale = tripDraftRepository.save(stale);
        TripDraft waiting = new TripDraft(user, List.of(1L), "1박 2일");
        waiting.markNeedsInput("지역을 나눌까요?", "{}");
        ReflectionTestUtils.setField(waiting, "updatedAt", LocalDateTime.now().minusMinutes(60));
        waiting = tripDraftRepository.save(waiting);
        TripDraft recent = tripDraftRepository.save(new TripDraft(user, List.of(1L), "1박 2일"));

        recoveryService.failStaleJobs();

        TripDraft failed = tripDraftRepository.findById(stale.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TripDraftStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(OrphanedJobRecoveryService.STALE_MESSAGE);
        assertThat(tripDraftRepository.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(TripDraftStatus.NEEDS_INPUT);
        assertThat(tripDraftRepository.findById(recent.getId()).orElseThrow().getStatus()).isEqualTo(TripDraftStatus.PENDING);
    }
}
