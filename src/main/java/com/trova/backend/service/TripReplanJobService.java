package com.trova.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.replan.TripReplanGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TripReplanJobService {

    private static final Logger log = LoggerFactory.getLogger(TripReplanJobService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TripReplanJobLifecycleService lifecycleService;
    private final TripReplanGraph tripReplanGraph;

    public TripReplanJobService(
            TripReplanJobLifecycleService lifecycleService,
            TripReplanGraph tripReplanGraph
    ) {
        this.lifecycleService = lifecycleService;
        this.tripReplanGraph = tripReplanGraph;
    }

    @Async("replanTaskExecutor")
    public void process(Long jobId) {
        try {
            TripReplanJobLifecycleService.JobContext context = lifecycleService.markProcessing(jobId);
            log.info("TripReplanJob {} 재구성 시작: tripId={}", jobId, context.trip().getId());

            TripReplanGraph.ReplanOutcome outcome = tripReplanGraph.run(
                    context.user(), context.trip(), context.indoorOnly(), context.allPlaces(),
                    (completed, total) -> {
                        try {
                            lifecycleService.updateProgress(jobId, completed, total);
                        } catch (Exception progressException) {
                            log.warn("TripReplanJob {} 진행률 갱신 실패 ({}/{}) — 처리는 계속한다",
                                    jobId, completed, total, progressException);
                        }
                    });

            String resultJson = MAPPER.writeValueAsString(outcome);
            lifecycleService.markDone(jobId, resultJson);
            log.info("TripReplanJob {} DONE", jobId);
        } catch (Exception e) {
            log.error("TripReplanJob {} 처리 실패", jobId, e);
            lifecycleService.markFailed(jobId, e.getMessage());
        }
    }
}
