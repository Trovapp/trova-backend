package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.geocoding.GeocodingResult;
import com.trova.backend.pipeline.ExtractedPlace;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AnalysisFinalizationIntegrationTest {
    @Autowired private ProcessingJobLifecycleService lifecycle;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private SavedPlaceRepository places;
    @Autowired private UserRepository users;
    private static final String USER_ID = "analysis-finalization-fixture";

    private ProcessingJob job() {
        User user = users.save(new User("google", USER_ID, "fixture", null));
        ProcessingJob job = jobs.save(new ProcessingJob(user, "https://youtu.be/fixture", SourcePlatform.YOUTUBE));
        lifecycle.startAnalysis(job.getId());
        return job;
    }

    private ExtractedPlace extracted(String name) {
        return new ExtractedPlace(name, "부산", "attraction", 0.9, 1, 1, List.of(name));
    }

    private List<SavedPlace> saved(ProcessingJob job) {
        return places.findByProcessingJob(job);
    }

    @AfterEach
    void 정리한다() {
        users.findByProviderAndProviderUserId("google", USER_ID).ifPresent(user -> {
            places.deleteAll(places.findByUserOrderByCreatedAtDescIdDesc(user));
            jobs.deleteAll(jobs.findByUserOrderByCreatedAtDescIdDesc(user));
            users.delete(user);
        });
    }

    @Test
    void 이전_프로세스의_부분_결과를_교체하고_전체_결과와_완료를_함께_저장한다() {
        ProcessingJob job = job();
        lifecycle.savePlace(job.getId(), extracted("이전 부분 결과"), GeocodingResult.empty());
        assertThat(saved(job)).hasSize(1);
        assertThat(lifecycle.completeAnalysis(job.getId(), List.of(extracted("해운대"), extracted("광안리")),
                List.of(GeocodingResult.empty(), GeocodingResult.empty()))).isTrue();
        assertThat(saved(job)).extracting(SavedPlace::getPlaceName).containsExactlyInAnyOrder("해운대", "광안리");
        ProcessingJob done = jobs.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
        assertThat(done.getAnalysisFinishedAt()).isNotNull();
    }

    @Test
    void 두번째_저장이_실패하면_삭제와_첫번째_저장과_완료도_롤백한다() {
        ProcessingJob job = job();
        lifecycle.savePlace(job.getId(), extracted("이전 부분 결과"), GeocodingResult.empty());
        Long oldId = saved(job).get(0).getId();
        assertThatThrownBy(() -> lifecycle.completeAnalysis(job.getId(),
                List.of(extracted("첫 새 결과"), extracted("x".repeat(300))),
                List.of(GeocodingResult.empty(), GeocodingResult.empty()))).isInstanceOf(RuntimeException.class);
        assertThat(saved(job)).extracting(SavedPlace::getId).containsExactly(oldId);
        ProcessingJob current = jobs.findById(job.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(current.getAnalysisStatus()).isNull();
        assertThat(current.getAnalysisFinishedAt()).isNull();
    }

    @Test
    void 완료된_작업의_재수신은_기존_ID와_결과를_유지한다() {
        ProcessingJob job = job();
        assertThat(lifecycle.completeAnalysis(job.getId(), List.of(extracted("첫 결과")), List.of(GeocodingResult.empty()))).isTrue();
        Long id = saved(job).get(0).getId();
        assertThat(lifecycle.completeAnalysis(job.getId(), List.of(extracted("덮어쓸 결과")), List.of(GeocodingResult.empty()))).isFalse();
        assertThat(saved(job)).extracting(SavedPlace::getId).containsExactly(id);
        assertThat(saved(job).get(0).getPlaceName()).isEqualTo("첫 결과");
    }

    @Test
    void 실패한_작업은_결과나_상태를_덮지_않는다() {
        ProcessingJob job = job();
        lifecycle.markAnalysisFailed(job.getId(), "중단");
        assertThat(lifecycle.completeAnalysis(job.getId(), List.of(extracted("새 결과")), List.of(GeocodingResult.empty()))).isFalse();
        assertThat(saved(job)).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void 입력_개수가_다르면_기존_부분_결과를_보존한다() {
        ProcessingJob job = job();
        lifecycle.savePlace(job.getId(), extracted("보존"), GeocodingResult.empty());
        assertThatThrownBy(() -> lifecycle.completeAnalysis(job.getId(), List.of(extracted("새 결과")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(saved(job)).extracting(SavedPlace::getPlaceName).containsExactly("보존");
    }
}
