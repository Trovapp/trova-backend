package com.trova.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "saved_places", indexes = {
        @Index(name = "idx_saved_places_user_created_id", columnList = "user_id, created_at, id")
})
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

    // 다른 장소들과 멀리 떨어져 있고 영상 근처에 같은 가게의 다른 지점이 있을 때 그 지점 이름(#414).
    // 예전엔 자동으로 그 지점으로 바꿨지만, 지점 자체가 목적지(스타벅스 더북한강R점)이거나 다른 도시를 함께 도는
    // 영상에서 틀리게 바뀌어 사용자가 "찾아서 고치기"로 직접 고르도록 표시만 한다.
    // 사용자가 위치를 바로잡기 전의 이름(영상에서 뽑힌 이름). 같은 영상을 다시 분석했을 때 이 이름의 장소에
    // 바로잡은 위치를 이어받게 한다(#416). 바로잡은 적이 없으면 null.
    @Column(name = "relocated_from_name")
    private String relocatedFromName;

    @Column(name = "nearby_branch_name")
    private String nearbyBranchName;

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
    public String getNearbyBranchName() { return nearbyBranchName; }

    public void suggestNearbyBranch(String branchName) {
        this.nearbyBranchName = branchName;
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getGooglePlaceId() { return googlePlaceId; }
    public String getOpeningPeriods() { return openingPeriods; }
    public LocalDateTime getHoursCheckedAt() { return hoursCheckedAt; }

    public void applyOpeningHours(String googlePlaceId, String openingPeriodsJson) {
        this.googlePlaceId = googlePlaceId;
        this.openingPeriods = openingPeriodsJson;
        this.hoursCheckedAt = LocalDateTime.now();
    }

    public List<String> getVideoNotes() {
        return videoNotes == null || videoNotes.isBlank() ? List.of() : List.of(videoNotes.split("\n"));
    }

    /**
     * 영상에서 말한 내용을 여행 장소 메모로 옮길 때의 모양(#134) — "· 내용" 줄들. 없으면 null.
     * 영상을 다시 보지 않아도 일정의 장소마다 영상 속 설명이 남게 하려는 것(사용자 방향, 2026-10-07).
     */
    public String videoNotesAsMemo() {
        List<String> notes = getVideoNotes();
        return notes.isEmpty() ? null : String.join("\n", notes.stream().map(n -> "· " + n).toList());
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

    /**
     * 위치를 못 찾은 장소를 사용자가 이름 검색으로 고른 실제 장소로 바로잡는다(#217, ADR 0006 보완).
     * 영상에서 잘못 들은 이름("파오스반브레드")도 실제 가게 이름으로 바꾼다. 카카오 정보·영업시간은 예전 이름 기준이라 지운다.
     */
    public void relocateTo(String placeName, Double latitude, Double longitude, String address,
                           String phone, String googlePlaceId) {
        if (this.relocatedFromName == null) {
            this.relocatedFromName = this.placeName;
        }
        this.placeName = placeName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
        this.roadAddress = null;
        this.phone = phone;
        this.kakaoCategoryName = null;
        this.kakaoPlaceUrl = null;
        this.googlePlaceId = googlePlaceId;
        this.openingPeriods = null;
        this.hoursCheckedAt = null;
        this.nearbyBranchName = null;
    }

    /**
     * 같은 영상을 다시 분석한 결과에, 이전에 사용자가 바로잡아 둔 위치를 이어받는다(#416). 다시 분석하면 새 장소가
     * 만들어져 직접 고친 위치가 원래대로 돌아갔다(수정 확인 QA 2026-11-12). 외부 API는 부르지 않는다.
     */
    public void inheritRelocation(SavedPlace previous) {
        String original = previous.relocatedFromName;
        relocateTo(previous.placeName, previous.latitude, previous.longitude, previous.address,
                previous.phone, previous.googlePlaceId);
        this.relocatedFromName = original;
    }

    public String getRelocatedFromName() { return relocatedFromName; }
}
