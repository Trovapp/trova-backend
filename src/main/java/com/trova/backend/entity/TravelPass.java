package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 여행 패스 한 장(#130) — 30일, 자동 갱신 아님. 이미 쓰는 중에 또 사면 끝나는 날부터 이어서 시작한다.
 * transactionId는 결제 거래 id라 같은 거래를 두 번 보내도 한 장만 생긴다.
 */
@Entity
@Table(name = "travel_passes")
public class TravelPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 100)
    private String transactionId;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    /** 거래가 어디서 왔는지 — Apple 운영·샌드박스, 또는 개발용 Xcode StoreKit 테스트. */
    @Column(nullable = false, length = 20)
    private String environment;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TravelPass() {
    }

    public TravelPass(User user, String transactionId, String productId, String environment,
                      LocalDateTime startsAt, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.user = user;
        this.transactionId = transactionId;
        this.productId = productId;
        this.environment = environment;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTransactionId() { return transactionId; }
    public String getProductId() { return productId; }
    public String getEnvironment() { return environment; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
