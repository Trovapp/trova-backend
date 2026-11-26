package com.trova.backend.service;

import com.trova.backend.admin.FailureReason;
import com.trova.backend.entity.JobStatus;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisResultService {
    private final ProcessingJobRepository jobs;
    private final SavedPlaceRepository places;

    public AnalysisResultService(ProcessingJobRepository jobs, SavedPlaceRepository places) {
        this.jobs = jobs;
        this.places = places;
    }

    public record Result(String status, long placeCount, String failureReason) {}

    // 상태와 장소 수를 동일 DB 스냅샷에서 읽는다. 분석 완료는 결과 저장과 함께 커밋된다(ADR 0029).
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result get(Long userId, Long jobId) {
        ProcessingJobRepository.AnalysisStatusRow job = jobs.findAnalysisStatusByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        JobStatus status = job.getAnalysisStatus() == null ? job.getStatus() : job.getAnalysisStatus();
        if (status == JobStatus.DONE) {
            return new Result("SUCCEEDED", places.countByProcessingJobId(jobId), null);
        }
        if (status == JobStatus.FAILED) {
            FailureReason reason = FailureReason.classify(job.getErrorMessage());
            return new Result("FAILED", 0, reason == FailureReason.STALE ? "OTHER" : reason.name());
        }
        return new Result(status == JobStatus.PROCESSING ? "PROCESSING" : "PENDING", 0, null);
    }
}
