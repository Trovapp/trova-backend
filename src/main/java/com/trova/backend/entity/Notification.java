package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 날씨 체크 결과로 생긴 인앱 알림. 폴링으로만 확인한다(푸시/이메일 없음 — Trova는
 * 웹이라 모바일 푸시 인프라가 없어서 0-1 원칙대로 Plan B와 다르게 감).
 *
 * 대안 장소는 더 이상 알림에 미리 계산해서 담아두지 않는다 — tripPlaceId로 그
 * 장소의 대안 찾기 화면(GET /api/trip-places/{id}/alternatives)을 직접 연다.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "precipitation_prob", nullable = false)
    private Double precipitationProb;

    @Column(name = "trip_place_id", nullable = false)
    private Long tripPlaceId;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public Notification(
            User user, Itinerary itinerary, String title, String body,
            Double precipitationProb, Long tripPlaceId
    ) {
        this.user = user;
        this.itinerary = itinerary;
        this.title = title;
        this.body = body;
        this.precipitationProb = precipitationProb;
        this.tripPlaceId = tripPlaceId;
        this.isRead = false;
        this.createdAt = LocalDateTime.now();
    }

    public void markRead() {
        this.isRead = true;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Itinerary getItinerary() { return itinerary; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Double getPrecipitationProb() { return precipitationProb; }
    public Long getTripPlaceId() { return tripPlaceId; }
    public boolean isRead() { return isRead; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
