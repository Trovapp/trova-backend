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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일정 에이전트의 초안(#106). 사용자 요청("이 영상 3개로 부산 1박 2일")과 해석 결과, 모은 장소 요약,
 * (2일차부터) 초안 일정, 시작 전 질문을 담는다. 승인 전까지는 여행(Trip)을 만들지 않는다.
 */
@Entity
@Table(name = "trip_drafts")
public class TripDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripDraftStatus status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    // 고른 영상들의 처리 작업 id(쉼표로 이음, 순서 유지).
    @Column(name = "job_ids", nullable = false)
    private String jobIds;

    private Integer days;

    @Column(name = "start_date")
    private LocalDate startDate;

    // 일수를 어디서 읽었는지: CODE / AI / DEFAULT — 측정에 쓴다.
    @Column(name = "request_source")
    private String requestSource;

    // 시작 전에 사용자에게 물어볼 것(예: 영상 지역이 멀 때). 없으면 null.
    @Column(columnDefinition = "TEXT")
    private String question;

    // 모은 장소·영업시간 확인 결과 요약(JSON).
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    // 초안 일정(JSON: 일차별 장소·시각, 뺀 장소와 이유, 숙소 안내, 가정). 2일차부터.
    @Column(name = "draft_json", columnDefinition = "TEXT")
    private String draftJson;

    // 이 초안을 만드는 데 쓴 Gemini 호출 수(요청 해석·초안·형식 재요청 합). eval에서 요청당 호출 수로 쓴다.
    @Column(name = "gemini_calls")
    private Integer geminiCalls;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TripDraft() {
    }

    public TripDraft(User user, List<Long> jobIds, String message) {
        this.user = user;
        this.jobIds = jobIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        this.message = message;
        this.status = TripDraftStatus.PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        touch(TripDraftStatus.PROCESSING);
    }

    public void applyRequest(int days, LocalDate startDate, String source) {
        this.days = days;
        this.startDate = startDate;
        this.requestSource = source;
        this.updatedAt = LocalDateTime.now();
    }

    public void markNeedsInput(String question, String summaryJson) {
        this.question = question;
        this.summaryJson = summaryJson;
        touch(TripDraftStatus.NEEDS_INPUT);
    }

    public void markReady(String summaryJson, String draftJson) {
        this.summaryJson = summaryJson;
        this.draftJson = draftJson;
        touch(TripDraftStatus.READY);
    }

    public void addGeminiCalls(int calls) {
        this.geminiCalls = (geminiCalls == null ? 0 : geminiCalls) + calls;
    }

    public void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        touch(TripDraftStatus.FAILED);
    }

    private void touch(TripDraftStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public TripDraftStatus getStatus() { return status; }
    public String getMessage() { return message; }
    public List<Long> getJobIds() { return Arrays.stream(jobIds.split(",")).map(Long::valueOf).toList(); }
    public Integer getDays() { return days; }
    public LocalDate getStartDate() { return startDate; }
    public String getRequestSource() { return requestSource; }
    public String getQuestion() { return question; }
    public String getSummaryJson() { return summaryJson; }
    public String getErrorMessage() { return errorMessage; }
    public String getDraftJson() { return draftJson; }
    public Integer getGeminiCalls() { return geminiCalls; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
