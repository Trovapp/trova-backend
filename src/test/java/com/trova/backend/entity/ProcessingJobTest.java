package com.trova.backend.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingJobTest {

    private ProcessingJob newJob() {
        return new ProcessingJob(new User("google", "job-test", "테스트", null), "https://youtu.be/x", SourcePlatform.YOUTUBE);
    }

    @Test
    void 분석이_끝나면_분석_결과와_끝난_시각을_남긴다() {
        ProcessingJob job = newJob();
        job.markProcessing();

        job.markAnalysisDone();

        assertThat(job.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getAnalysisFinishedAt()).isEqualTo(job.getUpdatedAt());
    }

    @Test
    void 분석_뒤_일정_생성이_상태를_바꿔도_분석_결과와_시각은_그대로다() {
        // 일정 생성(ItineraryGenerationService)은 끝난 같은 작업을 다시 PROCESSING→DONE/FAILED로 바꾼다(리뷰 지적, #161).
        ProcessingJob job = newJob();
        job.markAnalysisDone();
        LocalDateTime finishedAt = job.getAnalysisFinishedAt();

        job.markProcessing();
        job.markFailed("일정 생성 실패");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getAnalysisFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void 분석_결과가_이미_있으면_멈춘_작업_정리의_실패로_덮어쓰지_않는다() {
        ProcessingJob job = newJob();
        job.markAnalysisDone();
        job.markProcessing();

        job.markAnalysisFailed("멈춘 작업");

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void 분석이_실패하면_실패로_남긴다() {
        ProcessingJob job = newJob();

        job.markAnalysisFailed(ProcessingJob.NO_PLACES_MESSAGE);

        assertThat(job.getAnalysisStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo(ProcessingJob.NO_PLACES_MESSAGE);
    }

    @Test
    void 멈춘_작업으로_실패_처리된_분석이_나중에_끝나면_완료로_바뀐다() {
        // 외부 호출이 15분 넘게 걸리면 멈춘 작업 정리가 먼저 실패로 남긴다 — 그 뒤 분석이 실제로 끝나면 완료가 맞다(재리뷰 지적, #161).
        ProcessingJob job = newJob();
        job.markProcessing();
        job.markAnalysisFailed("멈춘 작업");

        job.markAnalysisDone();

        assertThat(job.getAnalysisStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getAnalysisFinishedAt()).isEqualTo(job.getUpdatedAt());
    }
}
