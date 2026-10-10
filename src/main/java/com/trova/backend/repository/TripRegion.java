package com.trova.backend.repository;

/** 여행 목록 요약(#153)에 쓰는 여행 장소 한 줄 — 엔티티 대신 여행 id와 지역만 읽는다. */
public record TripRegion(Long tripId, String region) {
}
