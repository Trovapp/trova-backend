package com.trova.backend.queue;

/** 영상 분석이 어느 대기열을 거쳐 시작됐는지(#249) — 지표 trova.queue.wait의 path 태그. */
public enum QueuePath {
    KAFKA("kafka"),
    ASYNC("async");

    private final String tag;

    QueuePath(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
