package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * iOS 단축어용 공유 전용 키(ADR 0013, #165). 사용자당 하나, 원문은 발급 때 한 번만 보여주고 DB에는 SHA-256 해시만 둔다.
 * 이 키로는 공유 저장(POST /api/shares)만 할 수 있다.
 */
@Entity
@Table(name = "share_keys")
public class ShareKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    protected ShareKey() {
    }

    public ShareKey(Long userId, String keyHash) {
        this.userId = userId;
        this.keyHash = keyHash;
        this.createdAt = LocalDateTime.now();
    }

    /** 재발급 — 이전 키는 해시가 바뀌어 바로 무효가 된다. */
    public void replace(String newKeyHash) {
        this.keyHash = newKeyHash;
        this.createdAt = LocalDateTime.now();
        this.lastUsedAt = null;
    }

    public void markUsed() {
        this.lastUsedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getKeyHash() { return keyHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
}
