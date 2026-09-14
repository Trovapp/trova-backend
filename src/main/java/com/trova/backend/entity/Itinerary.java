package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "itineraries")
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    // "day"는 H2에서 예약어라 컬럼명을 day_number로 분리한다(SavedPlace의 기존 컬럼명과도 일관됨).
    @Column(name = "day_number", nullable = false)
    private int day;

    private LocalDate date;

    protected Itinerary() {
    }

    public Itinerary(Trip trip, int day, LocalDate date) {
        this.trip = trip;
        this.day = day;
        this.date = date;
    }

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public int getDay() { return day; }
    public LocalDate getDate() { return date; }
}
