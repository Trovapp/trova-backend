package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_places")
public class SavedPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "processing_job_id", nullable = false)
    private ProcessingJob processingJob;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "place_name", nullable = false)
    private String placeName;

    private String region;

    private String category;

    private Double latitude;

    private Double longitude;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "title")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_platform", nullable = false)
    private SourcePlatform sourcePlatform;

    @Column(name = "day_number")
    private Integer dayNumber;

    @Column(name = "order_in_day")
    private Integer orderInDay;

    private String phone;

    private String address;

    @Column(name = "road_address")
    private String roadAddress;

    @Column(name = "kakao_category_name")
    private String kakaoCategoryName;

    @Column(name = "kakao_place_url")
    private String kakaoPlaceUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SavedPlace() {
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude) {
        this(processingJob, user, placeName, region, category, latitude, longitude, null, null,
                null, null, null, null, null);
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude,
                       Integer dayNumber, Integer orderInDay) {
        this(processingJob, user, placeName, region, category, latitude, longitude, dayNumber, orderInDay,
                null, null, null, null, null);
    }

    public SavedPlace(ProcessingJob processingJob, User user, String placeName, String region,
                       String category, Double latitude, Double longitude,
                       Integer dayNumber, Integer orderInDay,
                       String phone, String address, String roadAddress,
                       String kakaoCategoryName, String kakaoPlaceUrl) {
        this.processingJob = processingJob;
        this.user = user;
        this.placeName = placeName;
        this.region = region;
        this.category = category;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sourceUrl = processingJob.getSourceUrl();
        this.title = processingJob.getTitle();
        this.sourcePlatform = processingJob.getSourcePlatform();
        this.dayNumber = dayNumber;
        this.orderInDay = orderInDay;
        this.phone = phone;
        this.address = address;
        this.roadAddress = roadAddress;
        this.kakaoCategoryName = kakaoCategoryName;
        this.kakaoPlaceUrl = kakaoPlaceUrl;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public ProcessingJob getProcessingJob() { return processingJob; }
    public User getUser() { return user; }
    public String getPlaceName() { return placeName; }
    public String getRegion() { return region; }
    public String getCategory() { return category; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getSourceUrl() { return sourceUrl; }
    public String getTitle() { return title; }
    public SourcePlatform getSourcePlatform() { return sourcePlatform; }
    public Integer getDayNumber() { return dayNumber; }
    public Integer getOrderInDay() { return orderInDay; }
    public String getPhone() { return phone; }
    public String getAddress() { return address; }
    public String getRoadAddress() { return roadAddress; }
    public String getKakaoCategoryName() { return kakaoCategoryName; }
    public String getKakaoPlaceUrl() { return kakaoPlaceUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void assignToDay(Integer dayNumber, Integer orderInDay) {
        this.dayNumber = dayNumber;
        this.orderInDay = orderInDay;
    }
}
