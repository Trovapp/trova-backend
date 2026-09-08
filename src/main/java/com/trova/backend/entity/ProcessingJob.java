package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "title")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private SourcePlatform sourcePlatform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage")
    private ProcessingStage currentStage;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProcessingJob() {
    }

    public ProcessingJob(User user, String sourceUrl, SourcePlatform sourcePlatform) {
        this.user = user;
        this.sourceUrl = sourceUrl;
        this.sourcePlatform = sourcePlatform;
        this.status = JobStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = LocalDateTime.now();
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateStage(ProcessingStage stage) {
        this.currentStage = stage;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDone() {
        this.status = JobStatus.DONE;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount += 1;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getSourceUrl() { return sourceUrl; }
    public String getTitle() { return title; }
    public SourcePlatform getSourcePlatform() { return sourcePlatform; }
    public JobStatus getStatus() { return status; }
    public ProcessingStage getCurrentStage() { return currentStage; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
