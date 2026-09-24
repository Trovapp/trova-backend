package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 경계를 실제로 검증해야 하므로 일부러 클래스 레벨 {@code @Transactional}을 붙이지 않는다.
 * (ProcessingJobLifecycleServiceIntegrationTest와 동일한 이유)
 */
@SpringBootTest
class TripReplanJobLifecycleServiceIntegrationTest {

    private static final String PROVIDER_USER_ID = "replan-lifecycle-1";

    @Autowired
    private TripReplanJobLifecycleService lifecycleService;

    @Autowired
    private TripReplanJobRepository tripReplanJobRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .ifPresent(user -> {
                    tripReplanJobRepository.deleteAll(tripReplanJobRepository.findAll().stream()
                            .filter(j -> j.getUser().getId().equals(user.getId())).toList());
                    tripRepository.deleteAll(tripRepository.findByUserOrderByCreatedAtDesc(user));
                    userRepository.delete(user);
                });
    }

    private TripReplanJob newJob() {
        User user = userRepository.findByProviderAndProviderUserId("google", PROVIDER_USER_ID)
                .orElseGet(() -> userRepository.save(new User("google", PROVIDER_USER_ID, "재구성라이프사이클", null)));
        Trip trip = tripRepository.save(
                new Trip(user, "라이프사이클 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        return tripReplanJobRepository.save(new TripReplanJob(user, trip, true));
    }

    @Test
    void markProcessing_직후_다른_조회에서_PROCESSING과_컨텍스트가_보인다() {
        TripReplanJob job = newJob();

        TripReplanJobLifecycleService.JobContext context = lifecycleService.markProcessing(job.getId());

        assertThat(context.indoorOnly()).isTrue();
        assertThat(context.user().getId()).isEqualTo(job.getUser().getId());
        assertThat(context.trip().getId()).isEqualTo(job.getTrip().getId());

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    void updateProgress가_커밋된다() {
        TripReplanJob job = newJob();

        lifecycleService.updateProgress(job.getId(), 2, 5);

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getCompletedTargets()).isEqualTo(2);
        assertThat(reloaded.getTotalTargets()).isEqualTo(5);
    }

    @Test
    void markDone이_결과JSON과_함께_커밋된다() {
        TripReplanJob job = newJob();

        lifecycleService.markDone(job.getId(), "{\"matches\":[]}");

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reloaded.getResultJson()).isEqualTo("{\"matches\":[]}");
    }

    @Test
    void 최대길이를_넘는_에러메시지는_뒤쪽_2000자로_잘려_저장된다() {
        TripReplanJob job = newJob();
        String message = "y".repeat(1000) + "x".repeat(2000);

        lifecycleService.markFailed(job.getId(), message);

        TripReplanJob reloaded = tripReplanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorMessage()).hasSize(2000).isEqualTo("x".repeat(2000));
    }
}
