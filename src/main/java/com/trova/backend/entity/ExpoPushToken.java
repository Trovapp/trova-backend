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
 * 안드로이드 앱(Expo) 알림 토큰(ADR 0017, #190). 웹 구독(push_subscriptions)과 구조가 달라 따로 둔다.
 * user_id만 들고 FK는 두지 않는다 — 탈퇴 때 UserAccountService가 함께 지운다.
 */
@Entity
@Table(name = "expo_push_tokens", indexes = {
        @Index(name = "idx_expo_push_tokens_user", columnList = "user_id")
})
public class ExpoPushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 200)
    private String token;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    protected ExpoPushToken() {
    }

    public ExpoPushToken(Long userId, String token) {
        this.userId = userId;
        this.token = token;
        this.createdAt = LocalDateTime.now();
        this.lastSeenAt = this.createdAt;
    }

    /** 같은 기기에서 다시 등록 — 다른 사람이 로그인했을 수도 있어 사용자까지 덮어쓴다. */
    public void reassign(Long userId) {
        this.userId = userId;
        this.lastSeenAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getToken() { return token; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public LocalDateTime getLastSuccessAt() { return lastSuccessAt; }
}
