package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 일정 에이전트의 초안(#106). 사용자 요청("이 영상 3개로 부산 1박 2일")과 해석 결과, 모은 장소 요약,
 * (2일차부터) 초안 일정, 시작 전 질문을 담는다. 승인 전까지는 여행(Trip)을 만들지 않는다.
 */
@Entity
// 닫기나 장소 변경 표시가 늦게 커밋돼도 다른 트랜잭션이 확정한 상태를 덮지 않는다.
@DynamicUpdate
@Table(name = "trip_drafts", indexes = {
        @Index(name = "idx_draft_user_request_key", columnList = "user_id, request_key"),
        @Index(name = "idx_draft_user_replacement", columnList = "user_id, replaces_draft_id")
})
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

    // 시작 전 질문에 대한 답: SPLIT(지역별로 날 나누기) / ONLY(고른 영상만). 없으면 null.
    private String answer;

    // 승인해서 만든 여행 id. 승인 전에는 null.
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 공유한 영상 분석이 끝나 자동으로 만든 초안(#136). 닫은 초안은 행을 남겨 같은 영상의 중복 생성을 막는다.
    @Column(name = "auto_created", nullable = false, columnDefinition = "boolean default false")
    private boolean autoCreated = false;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    // "다시 짜기"로 만든 초안이 대신하는 예전 초안. 예전 것이 홈에 보이던 초안일 때만 채운다 — 이 값이 있으면
    // 직접 만든 초안이어도 홈 "준비된 일정"에 보인다(4차 수정 확인 QA, #424).
    @Column(name = "replaces_draft_id")
    private Long replacesDraftId;

    // 이 초안에 들어간 영상 장소의 위치를 사용자가 바로잡은 시각(#414). 초안을 자동으로 다시 만들지 않고(ADR 0006 —
    // 바로잡기는 외부 API를 새로 부르지 않는다) 앱이 "위치가 바뀌었어요 · 다시 짜기"를 보여 사용자가 고른다.
    @Column(name = "places_changed_at")
    private LocalDateTime placesChangedAt;

    // 홈 교체 대상이 아닌 직접 만든 초안도 같은 원본의 진행 작업을 확인할 수 있게 한다.
    @Column(name = "unlisted_source_id", updatable = false)
    private Long unlistedSourceId;

    // 구버전 INSERT는 NULL. 원본 번호 없는 구버전 행과 신버전의 독립 요청을 구분한다.
    @Column(name = "source_tracked", updatable = false)
    private Boolean sourceTracked = true;

    // 작업자가 오래된 엔티티를 저장해도 정리한 키가 되살아나지 않도록 INSERT에서만 쓴다.
    @Column(name = "request_key", updatable = false)
    private UUID requestKey;

    public void recordRequestKey(UUID key) {
        this.requestKey = key;
    }

    public void recordReplacementOf(TripDraft source) {
        if (source.isListed() && source.getTripId() == null) {
            replaces(source.getId());
        } else {
            this.unlistedSourceId = source.getId();
        }
    }

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

    public static TripDraft auto(User user, List<Long> jobIds, String message) {
        TripDraft draft = new TripDraft(user, jobIds, message);
        draft.autoCreated = true;
        return draft;
    }

    /** 홈 "준비된 일정"에 보이는 초안인지 — 자동 초안이거나, 그런 초안을 다시 짠 것. */
    public boolean isListed() {
        return autoCreated || replacesDraftId != null;
    }

    public void replaces(Long draftId) {
        this.replacesDraftId = draftId;
    }

    public void dismiss() {
        this.dismissedAt = LocalDateTime.now();
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

    /** 질문에 답하면 다시 대기열로 — ONLY면 고른 영상만 남긴다(호출한 쪽이 원래 영상의 부분집합인지 확인). */
    public void answer(String answer, List<Long> keepJobIds) {
        this.answer = answer;
        if (keepJobIds != null && !keepJobIds.isEmpty()) {
            this.jobIds = keepJobIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        touch(TripDraftStatus.PENDING);
    }

    /**
     * 초안에 들어간 영상 장소의 위치가 바뀌었다고 표시한다(#414). 상태·내용은 그대로 둔다 — 만드는 중이어도
     * 표시가 남아 끝난 뒤 앱이 다시 짜기를 권한다. 승인했거나 닫은 초안은 표시하지 않는다.
     */
    public boolean markPlacesChanged() {
        if (tripId != null || dismissedAt != null) {
            return false;
        }
        this.placesChangedAt = LocalDateTime.now();
        return true;
    }

    public void markApproved(Long tripId) {
        this.tripId = tripId;
        touch(TripDraftStatus.APPROVED);
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
    public String getAnswer() { return answer; }
    public Long getTripId() { return tripId; }
    public String getDraftJson() { return draftJson; }
    public Integer getGeminiCalls() { return geminiCalls; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public boolean isAutoCreated() { return autoCreated; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public Long getUnlistedSourceId() { return unlistedSourceId; }
    public Long getReplacesDraftId() { return replacesDraftId; }
    public LocalDateTime getPlacesChangedAt() { return placesChangedAt; }
}
