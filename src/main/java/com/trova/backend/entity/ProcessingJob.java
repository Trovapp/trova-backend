package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processing_jobs")
public class ProcessingJob {

    // 영상에서 장소를 하나도 못 찾은 경우의 실패 문구(#55). DB 컬럼을 늘리지 않고 errorMessage에 이 문구로 남기고,
    // 응답에서 이 문구와 같으면 failureReason=NO_PLACES로 알려준다.
    public static final String NO_PLACES_MESSAGE = "영상에서 장소를 찾지 못했어요";
    // Gemini 무료 하루 한도 소진(#63). 태평양 시간 자정(한국 오후 4~5시)에 초기화되기 전엔 다시 해도 실패한다.
    public static final String AI_QUOTA_MESSAGE = "오늘 AI 분석 한도를 다 썼어요";

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
    public boolean isNoPlacesFailure() { return status == JobStatus.FAILED && NO_PLACES_MESSAGE.equals(errorMessage); }
    public boolean isAiQuotaFailure() { return status == JobStatus.FAILED && AI_QUOTA_MESSAGE.equals(errorMessage); }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
