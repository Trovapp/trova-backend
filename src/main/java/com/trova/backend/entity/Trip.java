package com.trova.backend.entity;

import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trips")
public class Trip {

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ElementCollection
    @CollectionTable(name = "trip_members", joinColumns = @JoinColumn(name = "trip_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "user_id"}),
            indexes = @Index(columnList = "user_id,trip_id"))
    private List<TripMember> members = new ArrayList<>();

    public List<TripMember> getMembers() { return members; }

    protected Trip() {
    }

    public Trip(User user, String title, LocalDate startDate, LocalDate endDate) {
        this.user = user;
        members.add(new TripMember(user, TripMember.Role.OWNER));
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** 날짜를 정하거나 바꾼다 — 자동 초안으로 만든 여행은 날짜가 없다(#181). */
    public void applyDates(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.updatedAt = LocalDateTime.now();
    }

    /** 이름을 바꾼다(#221). 앞뒤 공백은 지운다. */
    public void applyTitle(String title) {
        this.title = title.trim();
        this.updatedAt = LocalDateTime.now();
    }

    public void touch() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTitle() { return title; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
