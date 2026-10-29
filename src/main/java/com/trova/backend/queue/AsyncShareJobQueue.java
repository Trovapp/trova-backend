package com.trova.backend.queue;

import com.trova.backend.service.PlaceExtractionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 지금까지의 방식 그대로 — pipelineTaskExecutor(동시 2·대기 50)에 넘긴다. 운영 기본값. */
@Component
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncShareJobQueue implements ShareJobQueue {

    private final PlaceExtractionService placeExtractionService;

    public AsyncShareJobQueue(PlaceExtractionService placeExtractionService) {
        this.placeExtractionService = placeExtractionService;
    }

    @Override
    public void enqueue(Long jobId) {
        placeExtractionService.process(jobId);
    }
}
