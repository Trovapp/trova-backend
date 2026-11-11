package com.trova.backend.queue;

/**
 * 일정 초안 생성 작업을 처리 대기열에 넣는다(#390, ADR 0021 확장). 구현은 영상 분석 대기열과 같은 설정 app.queue.type으로 고른다 —
 * async(기본): 서버 안 planTaskExecutor, kafka: 토픽 trova.draft-jobs.
 * 대기열이 받지 못하면 TaskRejectedException을 던진다. 부르는 쪽이 방금 만든 초안을 지운다.
 */
public interface DraftJobQueue {

    void enqueue(Long draftId);
}
