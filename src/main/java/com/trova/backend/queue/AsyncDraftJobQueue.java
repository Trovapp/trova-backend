package com.trova.backend.queue;

import com.trova.backend.service.TripPlannerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 지금까지의 방식 그대로 — planTaskExecutor(동시 1~2·대기 20)에 넘긴다. 코드 기본값. */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncDraftJobQueue implements DraftJobQueue {

    private final TripPlannerService tripPlannerService;

    public AsyncDraftJobQueue(TripPlannerService tripPlannerService) {
        this.tripPlannerService = tripPlannerService;
    }

    @Override
    public void enqueue(Long draftId) {
        tripPlannerService.process(draftId);
    }
}
