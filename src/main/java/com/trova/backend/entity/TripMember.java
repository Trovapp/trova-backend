package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Embeddable
public class TripMember {
    @Column(name = "analytics_event_id")
    private UUID analyticsEventId;
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    public enum Role { OWNER, EDITOR }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    protected TripMember() {}
    public TripMember(User user, Role role) {
        this.user = user;
        this.role = role;
        this.joinedAt = LocalDateTime.now();
        this.analyticsEventId = UUID.randomUUID();
    }
    public User getUser() { return user; }
    public Role getRole() { return role; }
    public UUID getAnalyticsEventId() { return analyticsEventId; }
}
