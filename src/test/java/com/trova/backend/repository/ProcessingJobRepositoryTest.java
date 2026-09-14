package com.trova.backend.repository;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProcessingJobRepositoryTest {

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 사용자의_작업을_최신순으로_조회한다() {
        User user = userRepository.save(new User("google", "1", "테스트유저", null));
        ProcessingJob older = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/a", SourcePlatform.YOUTUBE));
        ProcessingJob newer = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/b", SourcePlatform.YOUTUBE));

        List<ProcessingJob> jobs = processingJobRepository.findByUserOrderByCreatedAtDescIdDesc(user);

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).getId()).isEqualTo(newer.getId());
        assertThat(jobs.get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    void PENDING과_PROCESSING만_조회한다() {
        User user = userRepository.save(new User("google", "2", "테스트유저2", null));
        ProcessingJob pending = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/c", SourcePlatform.YOUTUBE));
        ProcessingJob done = processingJobRepository.save(
                new ProcessingJob(user, "https://youtu.be/d", SourcePlatform.YOUTUBE));
        done.markProcessing();
        done.markDone();
        processingJobRepository.save(done);

        List<ProcessingJob> active = processingJobRepository.findByUserAndStatusIn(
                user, List.of(JobStatus.PENDING, JobStatus.PROCESSING));

        assertThat(active).extracting(ProcessingJob::getId).containsExactly(pending.getId());
    }
}
