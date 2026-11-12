package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.queue.DraftJobQueue;
import com.trova.backend.queue.ReplanJobQueue;
import com.trova.backend.queue.ShareJobQueue;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.TripDraftRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 저장됐지만 대기열에서 사라진 작업을 실제 DB 조회 조건으로 고르는지 본다(ADR 0043).
 * 대기열은 가짜로 바꿔 실제 처리(외부 호출)는 하지 않는다. 같은 메모리 DB를 다른 테스트와 나눠 쓰므로 개수가 아니라 id로 확인한다.
 */
@SpringBootTest(properties = "app.queue.requeue.enabled=true")
class StuckJobRequeueServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "stuck-job-requeue-1";

    @Autowired private StuckJobRequeueService requeueService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripReplanJobRepository tripReplanJobRepository;
    @Autowired private TripDraftRepository tripDraftRepository;
    @MockitoBean private ShareJobQueue shareJobQueue;
    @MockitoBean private DraftJobQueue draftJobQueue;
    @MockitoBean private ReplanJobQueue replanJobQueue;

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
        return userRepository.save(new User("google", PROVIDER_USER_ID, "다시보내기", null));
    }

    private ProcessingJob processingJob(User user, JobStatus status, int minutesSinceUpdate) {
        ProcessingJob job = new ProcessingJob(user, "https://youtu.be/requeue-" + System.nanoTime(), SourcePlatform.YOUTUBE);
        if (status == JobStatus.PROCESSING) job.markProcessing();
        if (status == JobStatus.DONE) job.markDone();
        ReflectionTestUtils.setField(job, "updatedAt", LocalDateTime.now().minusMinutes(minutesSinceUpdate));
        return processingJobRepository.save(job);
    }

    @Test
    void 대기_3분_처리중_8분을_넘고_15분_전인_영상_작업만_다시_보낸다() {
        User user = user();
        ProcessingJob stuckPending = processingJob(user, JobStatus.PENDING, 5);
        ProcessingJob freshPending = processingJob(user, JobStatus.PENDING, 1);
        ProcessingJob stuckProcessing = processingJob(user, JobStatus.PROCESSING, 10);
        ProcessingJob busyProcessing = processingJob(user, JobStatus.PROCESSING, 5);
        ProcessingJob tooOld = processingJob(user, JobStatus.PENDING, 20);
        ProcessingJob done = processingJob(user, JobStatus.DONE, 5);

        requeueService.requeueStuckJobs(LocalDateTime.now());

        verify(shareJobQueue).enqueue(stuckPending.getId());
        verify(shareJobQueue).enqueue(stuckProcessing.getId());
        verify(shareJobQueue, never()).enqueue(freshPending.getId());
        verify(shareJobQueue, never()).enqueue(busyProcessing.getId());
        verify(shareJobQueue, never()).enqueue(tooOld.getId());
        verify(shareJobQueue, never()).enqueue(done.getId());
    }

    @Test
    void 멈춘_초안과_재구성도_다시_보내고_답을_기다리는_초안은_두다() {
        User user = user();
        TripDraft stuckDraft = new TripDraft(user, List.of(1L), "1박 2일");
        ReflectionTestUtils.setField(stuckDraft, "updatedAt", LocalDateTime.now().minusMinutes(5));
        stuckDraft = tripDraftRepository.save(stuckDraft);
        TripDraft waiting = new TripDraft(user, List.of(1L), "1박 2일");
        waiting.markNeedsInput("지역을 나눌까요?", "{}");
        ReflectionTestUtils.setField(waiting, "updatedAt", LocalDateTime.now().minusMinutes(5));
        waiting = tripDraftRepository.save(waiting);
        Trip trip = tripRepository.save(new Trip(user, "여행", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)));
        TripReplanJob stuckReplan = new TripReplanJob(user, trip, false);
        stuckReplan.markProcessing();
        ReflectionTestUtils.setField(stuckReplan, "updatedAt", LocalDateTime.now().minusMinutes(10));
        stuckReplan = tripReplanJobRepository.save(stuckReplan);

        requeueService.requeueStuckJobs(LocalDateTime.now());

        verify(draftJobQueue).enqueue(stuckDraft.getId());
        verify(draftJobQueue, never()).enqueue(waiting.getId());
        verify(replanJobQueue).enqueue(stuckReplan.getId());
    }
}
