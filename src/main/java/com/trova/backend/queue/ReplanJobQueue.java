package com.trova.backend.queue;

/**
 * 일정 재구성 작업을 처리 대기열에 넣는다(#396, ADR 0021 확장). 구현은 같은 설정 app.queue.type으로 고른다 —
 * async(기본): 서버 안 replanTaskExecutor, kafka: 토픽 trova.replan-jobs.
 * 대기열이 받지 못하면 TaskRejectedException을 던진다. 부르는 쪽이 방금 만든 작업을 지우고 알린다(#31).
 */
public interface ReplanJobQueue {

    void enqueue(Long jobId);
}
