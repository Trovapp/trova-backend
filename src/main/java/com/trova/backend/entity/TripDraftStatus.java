package com.trova.backend.entity;

/** 일정 초안의 진행 상태(#106). NEEDS_INPUT: 시작 전에 사용자에게 물어볼 것이 있어 멈춘 상태. */
public enum TripDraftStatus {
    PENDING, PROCESSING, NEEDS_INPUT, READY, FAILED
}
