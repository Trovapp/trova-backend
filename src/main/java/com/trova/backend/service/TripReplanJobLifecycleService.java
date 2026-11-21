package com.trova.backend.service;

import com.trova.backend.entity.JobStatus;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.repository.TripReplanJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripReplanJobLifecycleService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final TripReplanJobRepository tripReplanJobRepository;

    public TripReplanJobLifecycleService(TripReplanJobRepository tripReplanJobRepository) {
        this.tripReplanJobRepository = tripReplanJobRepository;
    }

    public record JobContext(User user, Trip trip, boolean indoorOnly, boolean allPlaces) {
    }

    @Transactional
    public JobContext markProcessing(Long jobId) {
        TripReplanJob job = getJob(jobId);
        TripAccess.requireMember(job.getTrip(), job.getUser());
        job.markProcessing();
        return new JobContext(job.getUser(), job.getTrip(), job.isIndoorOnly(), job.isAllPlaces());
    }

    /**
     * 처리를 시작해도 되는 작업이면 PROCESSING으로 바꾸고 문맥을 돌려준다. 이미 끝났거나(DONE·FAILED) 지워진 작업이면 null(#396) —
     * Kafka로 같은 작업을 다시 받았을 때 재구성을 다시 돌리지 않는다. PROCESSING은 처리 중 서버가 죽은 경우라 이어서 한다.
     */
    @Transactional
    public JobContext startProcessing(Long jobId) {
        TripReplanJob job = tripReplanJobRepository.findById(jobId).orElse(null);
        if (job == null || (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.PROCESSING)) {
            return null;
        }
        TripAccess.requireMember(job.getTrip(), job.getUser());
        job.markProcessing();
        return new JobContext(job.getUser(), job.getTrip(), job.isIndoorOnly(), job.isAllPlaces());
    }

    @Transactional
    public void updateProgress(Long jobId, int completed, int total) {
        getJob(jobId).updateProgress(completed, total);
    }

    @Transactional
    public void markDone(Long jobId, String resultJson) {
        getJob(jobId).markDone(resultJson);
    }

    @Transactional
    public void markFailed(Long jobId, String rawMessage) {
        getJob(jobId).markFailed(truncate(rawMessage));
    }

    private TripReplanJob getJob(Long jobId) {
        return tripReplanJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("TripReplanJob을 찾을 수 없습니다: " + jobId));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MESSAGE_MAX_LENGTH
                ? message.substring(message.length() - ERROR_MESSAGE_MAX_LENGTH)
                : message;
    }
}
