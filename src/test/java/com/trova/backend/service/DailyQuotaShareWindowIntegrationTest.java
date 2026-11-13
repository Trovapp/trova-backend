package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 링크 공유 한도는 최근 7일 창에서 실패하지 않은 작업만 센다(#431). 기본 한도 10개 그대로 본다.
 */
@SpringBootTest
@Transactional
class DailyQuotaShareWindowIntegrationTest {

    @Autowired
    private DailyQuotaService dailyQuotaService;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(new User("google", "quota-window", "창유저", null));
    }

    private void job(int n, JobStatus status, LocalDateTime createdAt) {
        ProcessingJob job = new ProcessingJob(user, "https://youtu.be/window-" + n + "-" + status, SourcePlatform.YOUTUBE);
        ReflectionTestUtils.setField(job, "status", status);
        ReflectionTestUtils.setField(job, "createdAt", createdAt);
        processingJobRepository.save(job);
    }

    @Test
    void 실패한_작업은_7일_한도에_세지_않는다() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 9; i++) {
            job(i, JobStatus.DONE, now.minusDays(i % 6));
        }
        for (int i = 0; i < 5; i++) {
            job(100 + i, JobStatus.FAILED, now.minusHours(i));
        }

        assertThatCode(() -> dailyQuotaService.checkShare(user)).doesNotThrowAnyException();
    }

    @Test
    void 진행_중인_작업도_세어_열_개가_차면_막는다() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 7; i++) {
            job(i, JobStatus.DONE, now.minusDays(1));
        }
        for (int i = 0; i < 3; i++) {
            job(200 + i, JobStatus.PROCESSING, now);
        }

        assertThatThrownBy(() -> dailyQuotaService.checkShare(user))
                .isInstanceOf(DailyQuotaService.QuotaExceededException.class)
                .hasMessage("최근 7일 동안 링크를 10개까지 추가할 수 있어요. 6일 뒤 다시 시도해주세요.");
    }

    @Test
    void 칠일보다_오래된_작업은_세지_않는다() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            job(i, JobStatus.DONE, now.minusDays(8).minusMinutes(i));
        }

        assertThatCode(() -> dailyQuotaService.checkShare(user)).doesNotThrowAnyException();
    }

    @Test
    void 가장_오래된_작업이_창을_벗어나는_날을_안내한다() {
        LocalDateTime now = LocalDateTime.now();
        job(0, JobStatus.DONE, now.minusDays(5).minusHours(1));
        for (int i = 1; i < 10; i++) {
            job(i, JobStatus.DONE, now.minusHours(i));
        }

        // 5일 1시간 전 작업은 1일 23시간 뒤에 창 밖으로 나간다 → 올림해 2일.
        assertThatThrownBy(() -> dailyQuotaService.checkShare(user))
                .hasMessage("최근 7일 동안 링크를 10개까지 추가할 수 있어요. 2일 뒤 다시 시도해주세요.");
    }
}
