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
    private final Object[] jobLocks = createJobLocks();

    private static Object[] createJobLocks() {
        Object[] locks = new Object[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    public TripReplanJobService(
            TripReplanJobLifecycleService lifecycleService,
            TripReplanGraph tripReplanGraph
    ) {
        this.lifecycleService = lifecycleService;
        this.tripReplanGraph = tripReplanGraph;
    }

    @Async("replanTaskExecutor")
    public void process(Long jobId) {
        processNow(jobId);
    }

    /**
     * 부른 스레드에서 끝까지 처리한다. Kafka 리스너(#396)는 처리가 끝난 뒤 메시지를 완료로 표시해야 서버가 중간에 죽었을 때
     * 같은 작업을 다시 받으므로 @Async를 거치지 않고 이걸 부른다. 같은 작업은 서버 한 대 안에서 한 번에 하나만 돈다.
     */
    public void processNow(Long jobId) {
        synchronized (jobLocks[Math.floorMod(jobId.hashCode(), jobLocks.length)]) {
            processSerially(jobId);
        }
    }

    private void processSerially(Long jobId) {
        try {
            TripReplanJobLifecycleService.JobContext context = lifecycleService.startProcessing(jobId);
            if (context == null) {
                log.info("TripReplanJob {} 중복 처리 건너뜀(완료·실패·삭제)", jobId);
                return;
            }
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
