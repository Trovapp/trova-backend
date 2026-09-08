package com.trova.backend.entity;

import jakarta.persistence.*;

/**
 * 사용자가 어떤 mood(분위기)를 선호하는지 학습한 점수. 지금은 북마크(양성 신호)만
 * 반영한다 — "추천했는데 안 고름"(음성 신호)은 프론트에 추천 화면이 아직 없어서
 * 그 신호 자체가 없다(0-1: Plan B 절반만 가져옴, 나머지는 프론트 붙을 때 추가).
 */
@Entity
@Table(name = "user_preferences", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "mood"}))
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String mood;

    @Column(nullable = false)
    private double score;

    protected UserPreference() {
    }

    public UserPreference(User user, String mood, double score) {
        this.user = user;
        this.mood = mood;
        this.score = score;
    }

    public void addScore(double delta) {
        this.score += delta;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getMood() { return mood; }
    public double getScore() { return score; }
}
