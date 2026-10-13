package com.trova.backend.admin.action;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@TestPropertySource(properties = "app.admin.dashboard.gemini-alert=0")
class RetryJobQuotaTest {

    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    @Autowired
    private RetryJobAction retry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @Test
    void 오늘_Gemini_사용량이_기준_이상이면_다시_돌리지_않는다() {
        User user = userRepository.save(new User("google", "retry-quota", "retry-quota", null));
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/retry-quota", SourcePlatform.YOUTUBE);
        job.markFailed(ProcessingJob.AI_QUOTA_MESSAGE);
        ProcessingJob saved = jobRepository.save(job);

        assertThatThrownBy(() -> retry.preview(saved.getId(), 1L))
                .satisfies(e -> {
                    assertThat(((AdminActionException) e).status().value()).isEqualTo(409);
                    assertThat(e.getMessage()).matches("오늘 Gemini 사용량이 \\d+회라 다시 실행하지 않아요\\.");
                });
        assertThatThrownBy(() -> retry.execute(saved.getId(), 1L))
                .isInstanceOf(AdminActionException.class);
        verify(placeExtractionService, never()).process(anyLong());
    }
}
