package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 한도로 세는 기능을 한 번 쓴 기록(#130) — 지금은 비서·대안·전체 재구성(ASSIST)만 여기 남긴다. */
@Entity
@Table(name = "usage_records", indexes = @Index(name = "idx_usage_user_feature_time", columnList = "user_id, feature, created_at"))
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MeteredFeature feature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected UsageRecord() {
    }

    public UsageRecord(User user, MeteredFeature feature, LocalDateTime createdAt) {
        this.user = user;
        this.feature = feature;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public MeteredFeature getFeature() { return feature; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
