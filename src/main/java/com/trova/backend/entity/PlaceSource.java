package com.trova.backend.entity;

/**
 * TripPlace가 어떻게 생겨났는지 구분한다. 지금은 영상 파이프라인에서 확정된 것(VIDEO)과
 * 카탈로그에서 추가하거나 공유 여행에서 복사한 것(NORMAL)을 구분한다.
 * 나중에 날씨 자동복구(WEATHER), 빈 시간 채우기(GAP) 등이 추가되면 여기에 값을 늘린다.
 */
public enum PlaceSource {
    VIDEO,
    NORMAL
}
