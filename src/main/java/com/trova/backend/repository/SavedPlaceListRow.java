package com.trova.backend.repository;

import com.trova.backend.entity.SourcePlatform;

import java.time.LocalDateTime;
import java.util.List;

/** 목록에 필요한 값만 조회한다. 작업/사용자 엔티티와 영업시간 원문은 로딩하지 않는다. */
public record SavedPlaceListRow(
        Long id, Long jobId, String jobSourceUrl,
        String placeName, String region, String category, Double latitude, Double longitude,
        String sourceUrl, String title, SourcePlatform sourcePlatform, LocalDateTime createdAt,
        Integer dayNumber, Integer orderInDay, String phone, String address, String roadAddress,
        String kakaoCategoryName, String kakaoPlaceUrl, String videoNotes, String nearbyBranchName
) {
    public List<String> videoNoteLines() {
        return videoNotes == null || videoNotes.isBlank() ? List.of() : List.of(videoNotes.split("\n"));
    }
}
