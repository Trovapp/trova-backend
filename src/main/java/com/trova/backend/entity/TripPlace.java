package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "trip_places")
public class TripPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(name = "place_name", nullable = false)
    private String placeName;

    private String region;

    private String category;

    private Double latitude;

    private Double longitude;

    private String phone;

    private String address;

    @Column(name = "visit_order", nullable = false)
    private int visitOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaceSource source;

    // VIDEO 출처일 때만 값이 있음 — 원본 SavedPlace 추적용(역참조, FK 아님)
    @Column(name = "saved_place_id")
    private Long savedPlaceId;

    // 날씨 자동복구용 실내/실외 태그. Gemini가 필요할 때만 태깅한다(null이면 아직 안 함).
    private String space;

    @Column(name = "google_place_id")
    private String googlePlaceId;

    @Column(name = "visit_start_time")
    private LocalTime visitStartTime;

    @Column(name = "visit_end_time")
    private LocalTime visitEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "arrival_transport_mode")
    private TransportMode arrivalTransportMode;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TripPlace() {
    }

    public TripPlace(
            Itinerary itinerary, String placeName, String region, String category,
            Double latitude, Double longitude, String phone, String address,
            int visitOrder, PlaceSource source, Long savedPlaceId
    ) {
        this.itinerary = itinerary;
        this.placeName = placeName;
        this.region = region;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.phone = phone;
        this.address = address;
        this.visitOrder = visitOrder;
        this.source = source;
        this.savedPlaceId = savedPlaceId;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Itinerary getItinerary() { return itinerary; }
    public String getPlaceName() { return placeName; }
    public String getRegion() { return region; }
    public String getCategory() { return category; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public int getVisitOrder() { return visitOrder; }
    public PlaceSource getSource() { return source; }
    public Long getSavedPlaceId() { return savedPlaceId; }
    public String getSpace() { return space; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getGooglePlaceId() { return googlePlaceId; }
    public LocalTime getVisitStartTime() { return visitStartTime; }
    public LocalTime getVisitEndTime() { return visitEndTime; }
    public TransportMode getArrivalTransportMode() { return arrivalTransportMode; }
    public String getMemo() { return memo; }

    public void applySpace(String space) {
        this.space = space;
    }

    public void applyVisitOrder(int visitOrder) {
        this.visitOrder = visitOrder;
    }

    public void applyGooglePlaceId(String googlePlaceId) {
        this.googlePlaceId = googlePlaceId;
    }

    public void applyDetails(
            LocalTime visitStartTime, LocalTime visitEndTime, TransportMode arrivalTransportMode, String memo
    ) {
        if (visitStartTime != null) this.visitStartTime = visitStartTime;
        if (visitEndTime != null) this.visitEndTime = visitEndTime;
        if (arrivalTransportMode != null) this.arrivalTransportMode = arrivalTransportMode;
        if (memo != null) this.memo = memo;
    }
}
