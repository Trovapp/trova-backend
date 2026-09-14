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

    // nullable — 미분류(폴더 없음)는 null로 표현한다. 별도의 "기본 폴더" row를
    // 만들지 않아서, 폴더를 지워도 찜이 고아가 되거나 사라지지 않는다.
    @ManyToOne
    @JoinColumn(name = "folder_id")
    private BookmarkFolder folder;

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
    public BookmarkFolder getFolder() { return folder; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void applyFolder(BookmarkFolder folder) {
        this.folder = folder;
    }
}

