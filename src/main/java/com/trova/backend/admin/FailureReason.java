package com.trova.backend.admin;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.service.OrphanedJobRecoveryService;

/** 처리 작업 실패 이유 — 실패 시 저장하는 문구(상수)로 나눈다. 새 실패 문구가 생기면 여기에 더한다. */
public enum FailureReason {
    NO_PLACES, AI_QUOTA, SOURCE_RATE_LIMITED, NO_MEDIA, STALE, OTHER;

    public static FailureReason classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return OTHER;
        }
        if (errorMessage.equals(ProcessingJob.NO_PLACES_MESSAGE)) {
            return NO_PLACES;
        }
        if (errorMessage.equals(ProcessingJob.AI_QUOTA_MESSAGE)) {
            return AI_QUOTA;
        }
        if (errorMessage.equals(ProcessingJob.SOURCE_RATE_LIMIT_MESSAGE)) {
            return SOURCE_RATE_LIMITED;
        }
        if (errorMessage.equals(ProcessingJob.NO_MEDIA_MESSAGE)) {
            return NO_MEDIA;
        }
        if (errorMessage.equals(OrphanedJobRecoveryService.STALE_MESSAGE)) {
            return STALE;
        }
        return OTHER;
    }
}
