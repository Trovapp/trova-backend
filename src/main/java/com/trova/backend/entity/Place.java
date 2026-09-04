package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Google Places 기반 장소 카탈로그(추천엔진 전용) — 영상에서 추출된 SavedPlace와는
 * 별개다. googlePlaceId로 캐시해서, 같은 장소를 반복 조회할 때 Google Places API를
 * 다시 호출하지 않는다. mood/space/category는 Gemini가 태깅하기 전까지 null이다.
 */
@Entity
@Table(name = "places")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_place_id", nullable = false, unique = true)
    private String googlePlaceId;

    @Column(nullable = false)
    private String name;

    // Google Places가 반환하는 원시 타입 중 첫번째(예: "cafe", "tourist_attraction")
    private String category;

    // Gemini가 태깅 — 태깅 전에는 null. 고정된 Java enum이 아니라 자유 텍스트로 둔다
    // (Gemini 응답이 정해진 분류값과 살짝 다르게 나올 여지가 있어서, self-repair로도
    // 못 맞추면 파싱이 깨지는 걸 막기 위함).
    private String mood;

    private String space;

    private Double rating;

    @Column(name = "user_rating_count")
    private Integer userRatingCount;

    @Column(name = "price_level")
    private String priceLevel;

    private Double latitude;

    private Double longitude;

    private String address;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    protected Place() {
    }

    public Place(
            String googlePlaceId, String name, String category, Double rating,
            Integer userRatingCount, String priceLevel, Double latitude, Double longitude, String address
    ) {
        this.googlePlaceId = googlePlaceId;
        this.name = name;
        this.category = category;
        this.rating = rating;
        this.userRatingCount = userRatingCount;
        this.priceLevel = priceLevel;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.lastSyncedAt = LocalDateTime.now();
    }

    public void applyTags(String mood, String space) {
        this.mood = mood;
        this.space = space;
    }

    public Long getId() { return id; }
    public String getGooglePlaceId() { return googlePlaceId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getMood() { return mood; }
    public String getSpace() { return space; }
    public Double getRating() { return rating; }
    public Integer getUserRatingCount() { return userRatingCount; }
    public String getPriceLevel() { return priceLevel; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getAddress() { return address; }
    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
}
