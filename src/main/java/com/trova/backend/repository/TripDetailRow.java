package com.trova.backend.repository;

import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TransportMode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 여행 상세(#476)의 일차 left join 장소 한 줄. 장소가 없는 일차는 장소 칸이 모두 null인 한 줄로 온다.
 * 엔티티 대신 응답에 쓰는 칸만 읽어 기본 EAGER 연관(일차 → 여행 → 사용자) 재조회를 막는다.
 */
public record TripDetailRow(
        Long itineraryId, int day, LocalDate date,
        Long placeId, String placeName, String region, String category,
        Double latitude, Double longitude, String phone, String address,
        Integer visitOrder, PlaceSource source, String googlePlaceId,
        LocalTime visitStartTime, LocalTime visitEndTime, TransportMode arrivalTransportMode, String memo
) {
}
