package com.trova.backend.queue;

import com.trova.backend.service.TripReplanJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 지금까지의 방식 그대로 — replanTaskExecutor에 넘긴다. 코드 기본값. */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncReplanJobQueue implements ReplanJobQueue {

    private final TripReplanJobService tripReplanJobService;

    public AsyncReplanJobQueue(TripReplanJobService tripReplanJobService) {
        this.tripReplanJobService = tripReplanJobService;
    }

    @Override
    public void enqueue(Long jobId) {
        tripReplanJobService.process(jobId);
    }
}
