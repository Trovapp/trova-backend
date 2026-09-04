package com.trova.backend.entity;

import jakarta.persistence.Embeddable;

/**
 * 날씨 알림에 담기는 실내 대안 장소 — 카카오 키워드 검색 결과를 그대로 임베드한다.
 * Place(구글 카탈로그)와 ID 체계가 달라 섞지 않고 별도로 둔다(0-1: provider 혼용 방지).
 */
@Embeddable
public class NotificationAlternative {

    private String name;
    private String address;
    private Double latitude;
    private Double longitude;

    protected NotificationAlternative() {
    }

    public NotificationAlternative(String name, String address, Double latitude, Double longitude) {
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getName() { return name; }
    public String getAddress() { return address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
}
