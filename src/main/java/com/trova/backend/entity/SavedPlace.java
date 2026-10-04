package com.trova.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

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

    // 일정 에이전트가 휴무·영업시간을 확인하려고 Google에서 받아 둔 정보(#106). 장소당 한 번만 받는다 —
    // 근처에 맞는 Google 장소가 없거나 영업시간이 없어도 확인 시각을 남겨 다시 묻지 않는다.
    @Column(name = "google_place_id")
    private String googlePlaceId;

    // Google regularOpeningHours.periods 원문(JSON). 없으면 null.
    @Column(name = "opening_periods", columnDefinition = "TEXT")
    private String openingPeriods;

    @Column(name = "hours_checked_at")
    private LocalDateTime hoursCheckedAt;

    // 영상이 이 장소에 대해 말하거나 보여준 구체 정보(#104). 한 줄짜리 문장 몇 개를 줄바꿈으로 이어 저장한다.
    @Column(name = "video_notes", columnDefinition = "TEXT")
    private String videoNotes;

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
    public String getGooglePlaceId() { return googlePlaceId; }
    public String getOpeningPeriods() { return openingPeriods; }
    public LocalDateTime getHoursCheckedAt() { return hoursCheckedAt; }

    public void applyOpeningHours(String googlePlaceId, String openingPeriodsJson) {
        this.googlePlaceId = googlePlaceId;
        this.openingPeriods = openingPeriodsJson;
        this.hoursCheckedAt = LocalDateTime.now();
    }

    /** 다른 기록에서 확인한 영업시간을 그대로 옮긴다(#132). 확인 시각도 원래 것을 둬, 복사가 이어져도 새것처럼 보이지 않게. */
    public void copyOpeningHoursFrom(SavedPlace other) {
        this.googlePlaceId = other.googlePlaceId;
        this.openingPeriods = other.openingPeriods;
        this.hoursCheckedAt = other.hoursCheckedAt;
    }

    public List<String> getVideoNotes() {
        return videoNotes == null || videoNotes.isBlank() ? List.of() : List.of(videoNotes.split("\n"));
    }

    public void applyVideoNotes(List<String> notes) {
        List<String> lines = notes == null ? List.of() : notes.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.replace('\n', ' ').trim())
                .toList();
        this.videoNotes = lines.isEmpty() ? null : String.join("\n", lines);
    }

    public void assignToDay(Integer dayNumber, Integer orderInDay) {
        this.dayNumber = dayNumber;
        this.orderInDay = orderInDay;
    }
}
