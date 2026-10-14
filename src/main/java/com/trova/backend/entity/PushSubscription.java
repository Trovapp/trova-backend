package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * PWA 웹 푸시 구독(ADR 0016). 기기(브라우저)마다 하나라 한 사용자가 여러 개를 가질 수 있다.
 * share_keys처럼 user_id만 들고 FK는 두지 않는다 — 탈퇴 때 UserAccountService가 함께 지운다.
 */
@Entity
@Table(name = "push_subscriptions", indexes = {
        @Index(name = "idx_push_subscriptions_user", columnList = "user_id")
})
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 1000)
    private String endpoint;

    @Column(nullable = false, length = 200)
    private String p256dh;

    @Column(nullable = false, length = 100)
    private String auth;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    protected PushSubscription() {
    }

    public PushSubscription(Long userId, String endpoint, String p256dh, String auth) {
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = LocalDateTime.now();
        this.lastSeenAt = this.createdAt;
    }

    /** 같은 기기에서 다시 등록 — 다른 사람이 로그인했을 수도 있어 사용자까지 덮어쓴다. */
    public void reassign(Long userId, String p256dh, String auth) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.lastSeenAt = LocalDateTime.now();
    }

    public void markSuccess() {
        this.lastSuccessAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
}
