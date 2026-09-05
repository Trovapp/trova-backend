package com.trova.backend.entity;

/** TripPlace에 도착할 때 이전 장소에서 쓴 이동수단. 하루의 첫 장소는 항상 null이다. */
public enum TransportMode {
    WALK,
    TRANSIT,
    CAR
}
