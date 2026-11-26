package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자 FK를 두지 않아 탈퇴 후에도 삭제해야 할 이전 분석 ID를 잃지 않는다. */
@Entity
@Table(name = "analytics_deletion_jobs")
public class AnalyticsDeletionJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "analytics_id", nullable = false, unique = true)
    private UUID analyticsId;
    @Column(nullable = false, length = 20)
    private String status = "PENDING";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AnalyticsDeletionJob() {}

    public AnalyticsDeletionJob(UUID analyticsId) {
        this.analyticsId = analyticsId;
        this.createdAt = LocalDateTime.now();
    }
}
