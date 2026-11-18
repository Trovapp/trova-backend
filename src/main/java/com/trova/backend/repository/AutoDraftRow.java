package com.trova.backend.repository;

import com.trova.backend.entity.TripDraftStatus;

import java.time.LocalDateTime;

/** 홈 카드에 필요한 값만 읽는다. 사용자와 상세 요약은 로딩하지 않는다. */
public record AutoDraftRow(Long id, TripDraftStatus status, String jobIds, Integer days,
                           LocalDateTime createdAt, String draftJson) {
    public Long firstJobId() {
        int comma = jobIds.indexOf(',');
        return Long.valueOf(comma < 0 ? jobIds : jobIds.substring(0, comma));
    }
}
