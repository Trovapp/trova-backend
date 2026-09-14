package com.trova.backend.entity;

/** 개인화 랭킹에 쓰는 사용자 긍정 신호 종류. v1은 전부 동일 가중치로 취급한다. */
public enum SignalType {
    BOOKMARK,
    TRIP_PLACE_ADDED,
    ALTERNATIVE_REPLACED,
    GAP_INSERTED,
    VIDEO_PLACE_MATCHED,
    // 대화형 비서(Phase 2)에서 특정 후보를 마음에 들어한다고 말했을 때 기록.
    CHAT_LIKED
}
