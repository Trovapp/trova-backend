package com.trova.backend.admin.action;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.OrphanedJobRecoveryService;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class RetryJobActionTest {

    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    @Autowired
    private RetryJobAction retry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @Autowired
    private SuspendedUsers suspendedUsers;

    private ProcessingJob failedJob(String sub, String message) {
        User user = userRepository.save(new User("google", sub, sub, null));
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/" + sub, SourcePlatform.YOUTUBE);
        job.markFailed(message);
        return jobRepository.save(job);
    }

    private static void assertConflict(Throwable e, String message) {
        assertThat(e).isInstanceOf(AdminActionException.class);
        assertThat(((AdminActionException) e).status().value()).isEqualTo(409);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    @Test
    void 일시_오류로_실패한_작업은_새_작업을_만들어_다시_돌린다() {
        ProcessingJob job = failedJob("retry-1", ProcessingJob.AI_QUOTA_MESSAGE);

        ActionPreview preview = retry.preview(job.getId(), 1L);
        assertThat(preview.action()).isEqualTo(AdminAction.RETRY_JOB);
        assertThat(preview.targetUserId()).isEqualTo(job.getUser().getId());
        assertThat(preview.impact()).startsWith("영상 1·오늘 Gemini ").endsWith("회");
        assertThat(preview.confirmText()).isNull();

        String result = retry.execute(job.getId(), 1L);

        assertThat(result).startsWith("새 작업 #");
        Long newId = Long.valueOf(result.substring("새 작업 #".length()));
        assertThat(newId).isNotEqualTo(job.getId());
        ProcessingJob newJob = jobRepository.findById(newId).orElseThrow();
        assertThat(newJob.getSourceUrl()).isEqualTo(job.getSourceUrl());
        assertThat(newJob.getSourcePlatform()).isEqualTo(SourcePlatform.YOUTUBE);
        assertThat(newJob.getUser().getId()).isEqualTo(job.getUser().getId());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getErrorMessage()).isEqualTo(ProcessingJob.AI_QUOTA_MESSAGE);
        verify(placeExtractionService).process(newId);
    }

    @Test
    void 멈춰서_실패한_작업과_인스타_요청_제한도_다시_돌릴_수_있다() {
        ProcessingJob stale = failedJob("retry-3", OrphanedJobRecoveryService.STALE_MESSAGE);
        ProcessingJob rateLimited = failedJob("retry-4", ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE);

        assertThat(retry.preview(stale.getId(), 1L).targetId()).isEqualTo(stale.getId());
        assertThat(retry.preview(rateLimited.getId(), 1L).targetId()).isEqualTo(rateLimited.getId());
    }

    @Test
    void 장소가_없어_실패한_작업은_다시_돌리지_않는다() {
        ProcessingJob job = failedJob("retry-2", ProcessingJob.NO_PLACES_MESSAGE);

        assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "일시 오류로 실패한 작업만 다시 실행해요."));
        assertThatThrownBy(() -> retry.execute(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "일시 오류로 실패한 작업만 다시 실행해요."));
        verify(placeExtractionService, never()).process(anyLong());
    }

    @Test
    void 실패하지_않은_작업은_다시_돌리지_않는다() {
        User user = userRepository.save(new User("google", "retry-5", "retry-5", null));
        ProcessingJob job = jobRepository.save(new ProcessingJob(user, "https://www.youtube.com/shorts/retry-5", SourcePlatform.YOUTUBE));

        assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "일시 오류로 실패한 작업만 다시 실행해요."));
    }

    @Test
    void 없는_작업이면_404() {
        assertThatThrownBy(() -> retry.preview(999_999L, 1L))
                .satisfies(e -> assertThat(((AdminActionException) e).status().value()).isEqualTo(404));
    }

    @Test
    void 같은_링크가_이미_처리_중이면_다시_돌리지_않는다() {
        ProcessingJob job = failedJob("retry-6", ProcessingJob.AI_QUOTA_MESSAGE);
        jobRepository.save(new ProcessingJob(job.getUser(), job.getSourceUrl(), SourcePlatform.YOUTUBE));

        assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "같은 링크를 이미 처리하고 있어요."));
        assertThatThrownBy(() -> retry.execute(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "같은 링크를 이미 처리하고 있어요."));
        verify(placeExtractionService, never()).process(anyLong());
    }

    @Test
    void 다시_돌린_새_작업이_끝나면_예전_실패_작업은_다시_돌리지_않는다() {
        ProcessingJob job = failedJob("retry-9", ProcessingJob.AI_QUOTA_MESSAGE);
        String result = retry.execute(job.getId(), 1L);
        ProcessingJob newJob = jobRepository.findById(Long.valueOf(result.substring("새 작업 #".length()))).orElseThrow();
        newJob.markDone();
        jobRepository.save(newJob);

        assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "이 영상은 더 최근 작업이 있어 다시 실행하지 않아요."));
        assertThatThrownBy(() -> retry.execute(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "이 영상은 더 최근 작업이 있어 다시 실행하지 않아요."));
        assertThat(retry.isRetryable(jobRepository.findById(job.getId()).orElseThrow())).isFalse();
        verify(placeExtractionService).process(newJob.getId());
    }

    @Test
    void 같은_링크의_더_최근_작업도_실패했으면_가장_최근_것만_다시_돌릴_수_있다() {
        ProcessingJob old = failedJob("retry-10", ProcessingJob.AI_QUOTA_MESSAGE);
        ProcessingJob newer = new ProcessingJob(old.getUser(), old.getSourceUrl(), SourcePlatform.YOUTUBE);
        newer.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        jobRepository.save(newer);

        assertThat(retry.isRetryable(old)).isFalse();
        assertThat(retry.isRetryable(newer)).isTrue();
        assertThatThrownBy(() -> retry.preview(old.getId(), 1L))
                .satisfies(e -> assertConflict(e, "이 영상은 더 최근 작업이 있어 다시 실행하지 않아요."));
        assertThat(retry.preview(newer.getId(), 1L).targetId()).isEqualTo(newer.getId());
    }

    @Test
    void 정지된_계정의_작업은_다시_돌리지_않는다() {
        ProcessingJob job = failedJob("retry-7", ProcessingJob.AI_QUOTA_MESSAGE);
        Long userId = job.getUser().getId();
        suspendedUsers.add(userId);
        try {
            assertThatThrownBy(() -> retry.preview(job.getId(), 1L))
                    .satisfies(e -> assertConflict(e, "정지된 계정의 작업은 다시 실행하지 않아요."));
            assertThatThrownBy(() -> retry.execute(job.getId(), 1L))
                    .satisfies(e -> assertConflict(e, "정지된 계정의 작업은 다시 실행하지 않아요."));
        } finally {
            suspendedUsers.remove(userId);
        }
        verify(placeExtractionService, never()).process(anyLong());
    }

    @Test
    void 실행기가_거절하면_새_작업을_지우고_409() {
        ProcessingJob job = failedJob("retry-8", ProcessingJob.AI_QUOTA_MESSAGE);
        doThrow(new TaskRejectedException("full")).when(placeExtractionService).process(anyLong());

        assertThatThrownBy(() -> retry.execute(job.getId(), 1L))
                .satisfies(e -> assertConflict(e, "서버가 바빠요. 잠시 뒤 다시 해 주세요."));

        List<ProcessingJob> jobs = jobRepository.findAll().stream()
                .filter(j -> j.getSourceUrl().equals(job.getSourceUrl()))
                .toList();
        assertThat(jobs).extracting(ProcessingJob::getId).containsExactly(job.getId());
    }
}
