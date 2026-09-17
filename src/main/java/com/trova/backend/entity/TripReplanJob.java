package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_replan_jobs")
public class TripReplanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "indoor_only", nullable = false)
    private boolean indoorOnly;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "completed_targets", nullable = false)
    private int completedTargets;

    // identify_targets가 끝나기 전까지는 총 타겟 수를 모르므로 null 허용.
    @Column(name = "total_targets")
    private Integer totalTargets;

    // ReplanOutcome을 JSON으로 직렬화해 저장 — DONE일 때만 채워짐. 재구성 결과는
    // 영속 개념이 아니라 미리보기용 임시 데이터라(확정은 항상 별도 replacePlace
    // 호출로 일어남) 별도 관계형 테이블을 만들지 않는다.
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TripReplanJob() {
    }

    public TripReplanJob(User user, Trip trip, boolean indoorOnly) {
        this.user = user;
        this.trip = trip;
        this.indoorOnly = indoorOnly;
        this.status = JobStatus.PENDING;
        this.completedTargets = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateProgress(int completed, int total) {
        this.completedTargets = completed;
        this.totalTargets = total;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDone(String resultJson) {
        this.status = JobStatus.DONE;
        this.resultJson = resultJson;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Trip getTrip() { return trip; }
    public boolean isIndoorOnly() { return indoorOnly; }
    public JobStatus getStatus() { return status; }
    public int getCompletedTargets() { return completedTargets; }
    public Integer getTotalTargets() { return totalTargets; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
