package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_consents", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "type", "version"}))
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentType type;

    @Column(nullable = false)
    private String version;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private LocalDateTime agreedAt;

    protected UserConsent() {
    }

    public UserConsent(User user, ConsentType type, String version) {
        this.user = user;
        this.type = type;
        this.version = version;
        this.agreedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ConsentType getType() {
        return type;
    }

    public String getVersion() {
        return version;
    }

    public LocalDateTime getAgreedAt() {
        return agreedAt;
    }
}
