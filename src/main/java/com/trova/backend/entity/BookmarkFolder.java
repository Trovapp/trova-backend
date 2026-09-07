package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** 사용자가 찜한 장소를 정리하는 폴더(네이버 지도의 "목록"과 동일한 역할). */
@Entity
@Table(name = "bookmark_folders")
public class BookmarkFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    // 지도 핀 색상으로 쓰는 hex 문자열(예: "#4A90D9")
    @Column(nullable = false)
    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected BookmarkFolder() {
    }

    public BookmarkFolder(User user, String name, String color) {
        this.user = user;
        this.name = name;
        this.color = color;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
