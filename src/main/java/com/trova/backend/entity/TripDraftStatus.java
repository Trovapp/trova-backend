package com.trova.backend.entity;

/**
 * 일정 초안의 진행 상태(#106). NEEDS_INPUT: 시작 전에 사용자에게 물어볼 것이 있어 멈춘 상태.
 * APPROVED: 사용자가 승인해 여행(Trip)으로 저장한 상태 — 이후 초안은 바뀌지 않는다.
 */
public enum TripDraftStatus {
    PENDING, PROCESSING, NEEDS_INPUT, READY, APPROVED, FAILED
}
