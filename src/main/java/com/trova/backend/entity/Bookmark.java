package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 추천엔진(Place 카탈로그)에서 나온 장소를 Trip과 무관하게 즐겨찾기한다. */
@Entity
@Table(name = "bookmarks", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "place_id"}))
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Bookmark() {
    }

    public Bookmark(User user, Place place) {
        this.user = user;
        this.place = place;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Place getPlace() { return place; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
