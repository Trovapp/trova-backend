package com.trova.backend.recommendation;

import com.trova.backend.entity.TransportMode;

/** 대안 찾기 필터 — 전부 nullable(안 걸면 그 조건은 무시). */
public record AlternativeFilter(
        String category,
        Boolean indoorOnly,
        Double maxDistanceKm,
        Integer maxTravelMinutes,
        TransportMode transportMode
) {
}
