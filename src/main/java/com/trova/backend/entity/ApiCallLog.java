package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 유료 전환 가능성이 있는 외부 API(Gemini, 카카오 등) 호출 1건당 지연시간·토큰·성공여부를
 * 기록한다. 트래픽당 예상 비용을 나중에 계산할 근거를 남기기 위함 — 무료 티어를 쓰고
 * 있어도 기록한다.
 */
@Entity
@Table(name = "api_call_logs")
public class ApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String operation;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "response_tokens")
    private Integer responseTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ApiCallLog() {
    }

    public ApiCallLog(
            String provider, String operation, Long jobId, long latencyMs, boolean success,
            String errorMessage, Integer promptTokens, Integer responseTokens, Integer totalTokens
    ) {
        this.provider = provider;
        this.operation = operation;
        this.jobId = jobId;
        this.latencyMs = latencyMs;
        this.success = success;
        this.errorMessage = errorMessage;
        this.promptTokens = promptTokens;
        this.responseTokens = responseTokens;
        this.totalTokens = totalTokens;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getOperation() { return operation; }
    public Long getJobId() { return jobId; }
    public long getLatencyMs() { return latencyMs; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getResponseTokens() { return responseTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
