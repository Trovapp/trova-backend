package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deferred_draft_requests",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "request_key"}),
        indexes = @Index(name = "idx_deferred_draft_content", columnList = "user_id, request_hash"))
public class DeferredDraftRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "request_key", nullable = false)
    private UUID requestKey;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected DeferredDraftRequest() {}

    public DeferredDraftRequest(User user, UUID requestKey, String requestHash, LocalDateTime createdAt) {
        this.user = user;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }
}
