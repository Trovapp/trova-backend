package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "trip_activity", indexes = @Index(columnList = "trip_id,id"))
public class TripActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Trip trip;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String summary;
    @Column(nullable = false, name = "created_at")
    private LocalDateTime createdAt;
    protected TripActivity() {}
}
