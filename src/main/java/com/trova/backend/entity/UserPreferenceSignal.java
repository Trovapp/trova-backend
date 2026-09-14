package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 사용자가 실제로 좋아한(북마크/여행에 담음/대안 교체 선택 등) 장소 이력. 개인화
 * 랭킹(PersonalizationService)이 이 이력 중 후보와 임베딩이 비슷한 것들을 찾아
 * 점수를 매긴다. v1은 긍정 신호만 기록한다 — 부정 신호(추천했는데 안 고름)는 범위 밖.
 */
@Entity
@Table(name = "user_preference_signals")
public class UserPreferenceSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false)
    private SignalType signalType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected UserPreferenceSignal() {
    }

    public UserPreferenceSignal(User user, Place place, SignalType signalType) {
        this.user = user;
        this.place = place;
        this.signalType = signalType;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Place getPlace() { return place; }
    public SignalType getSignalType() { return signalType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
