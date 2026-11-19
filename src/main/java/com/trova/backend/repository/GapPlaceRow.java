package com.trova.backend.repository;

import java.time.LocalTime;

/**
 * 빈 시간 추천(#482)이 읽는 일차 한 줄 — 일차 left join 장소라 장소가 없는 일차는 placeId가 null인 한 줄이다.
 * 빈 시간 계산에 필요한 칸만 읽어 여행·사용자·일차 엔티티를 다시 읽지 않는다.
 */
public record GapPlaceRow(
        Long itineraryId, Long placeId, LocalTime visitStartTime, LocalTime visitEndTime,
        Double latitude, Double longitude, String googlePlaceId
) {
}
