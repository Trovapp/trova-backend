package com.trova.backend.service;

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

    public record JobContext(User user, Trip trip, boolean indoorOnly) {
    }

    @Transactional
    public JobContext markProcessing(Long jobId) {
        TripReplanJob job = getJob(jobId);
        job.markProcessing();
        return new JobContext(job.getUser(), job.getTrip(), job.isIndoorOnly());
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
