package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 탈퇴한 회원의 여행 패스 구매 기록(#130). 「전자상거래 등에서의 소비자보호에 관한 법률」상 대금 결제·재화 공급 기록은
 * 5년 보관해야 해서, 탈퇴 때 지우지 않고 회원과 연결을 끊은 채 여기로 옮긴다(분리 보관).
 * 회원 id·닉네임 등은 남기지 않고 거래 자체의 정보만 둔다 — 환불·분쟁 때 Apple 거래 번호로 찾는다.
 * retainUntil이 지나면 PurchaseRetentionService가 지운다.
 */
@Entity
@Table(name = "retained_purchases")
public class RetainedPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 100)
    private String transactionId;

    @Column(name = "product_id", nullable = false, length = 100)
    private String productId;

    @Column(nullable = false, length = 20)
    private String environment;

    @Column(name = "purchased_at", nullable = false)
    private LocalDateTime purchasedAt;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "withdrawn_at", nullable = false)
    private LocalDateTime withdrawnAt;

    @Column(name = "retain_until", nullable = false)
    private LocalDateTime retainUntil;

    protected RetainedPurchase() {
    }

    public RetainedPurchase(TravelPass pass, LocalDateTime withdrawnAt, LocalDateTime retainUntil) {
        this.transactionId = pass.getTransactionId();
        this.productId = pass.getProductId();
        this.environment = pass.getEnvironment();
        this.purchasedAt = pass.getCreatedAt();
        this.startsAt = pass.getStartsAt();
        this.expiresAt = pass.getExpiresAt();
        this.withdrawnAt = withdrawnAt;
        this.retainUntil = retainUntil;
    }

    public Long getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public String getProductId() { return productId; }
    public String getEnvironment() { return environment; }
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getWithdrawnAt() { return withdrawnAt; }
    public LocalDateTime getRetainUntil() { return retainUntil; }
}
