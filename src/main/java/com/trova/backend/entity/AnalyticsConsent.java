package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자 행 잠금을 얻은 뒤에만 변경한다. 분석 ID는 이 전용 연결에만 보관한다. */
@Entity
@Table(name = "analytics_consents")
public class AnalyticsConsent {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false)
    private long revision;
    @Column(name = "usage_consent", nullable = false)
    private boolean usageConsent;
    @Column(name = "transfer_consent", nullable = false)
    private boolean transferConsent;
    @Column(name = "analytics_id", unique = true)
    private UUID analyticsId;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Column(name = "last_put_revision")
    private Long lastPutRevision;

    protected AnalyticsConsent() {}

    public AnalyticsConsent(Long userId) {
        this.userId = userId;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean matches(boolean usage, boolean transfer) {
        return usageConsent == usage && transferConsent == transfer;
    }

    public boolean isRetry(long expected, boolean usage, boolean transfer) {
        return lastPutRevision != null && lastPutRevision == expected && matches(usage, transfer);
    }

    public void change(boolean usage, boolean transfer, Long expectedRevision) {
        if (revision >= 9_007_199_254_740_991L) {
            throw new IllegalStateException("분석 동의 revision 상한");
        }
        usageConsent = usage;
        transferConsent = transfer;
        analyticsId = usage && transfer ? (analyticsId == null ? UUID.randomUUID() : analyticsId) : null;
        revision++;
        lastPutRevision = expectedRevision;
        updatedAt = LocalDateTime.now();
    }

    public long getRevision() { return revision; }
    public boolean isUsageConsent() { return usageConsent; }
    public boolean isTransferConsent() { return transferConsent; }
    public UUID getAnalyticsId() { return analyticsId; }
}
