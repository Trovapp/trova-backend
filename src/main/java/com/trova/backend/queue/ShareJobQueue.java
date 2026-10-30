package com.trova.backend.queue;

/**
 * 영상 분석 작업을 처리 대기열에 넣는다(#244, ADR 0021). 구현은 설정 app.queue.type으로 고른다 —
 * async(기본): 서버 안 @Async 실행기, kafka: Kafka 토픽.
 * 대기열이 받지 못하면 TaskRejectedException을 던진다. 부르는 쪽은 방금 만든 작업을 지우고 503으로 알린다(#31).
 */
public interface ShareJobQueue {

    void enqueue(Long jobId);
}
