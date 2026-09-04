package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 날씨 체크 결과로 생긴 인앱 알림. 폴링으로만 확인한다(푸시/이메일 없음 — Trova는
 * 웹이라 모바일 푸시 인프라가 없어서 0-1 원칙대로 Plan B와 다르게 감).
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

    @ElementCollection
    @CollectionTable(name = "notification_alternatives", joinColumns = @JoinColumn(name = "notification_id"))
    private List<NotificationAlternative> alternatives;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public Notification(
            User user, Itinerary itinerary, String title, String body,
            Double precipitationProb, List<NotificationAlternative> alternatives
    ) {
        this.user = user;
        this.itinerary = itinerary;
        this.title = title;
        this.body = body;
        this.precipitationProb = precipitationProb;
        this.alternatives = alternatives;
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
    public List<NotificationAlternative> getAlternatives() { return alternatives; }
    public boolean isRead() { return isRead; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
