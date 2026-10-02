package com.trova.backend.controller;

import com.trova.backend.entity.SavedPlace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 같은 영상의 성공 결과가 여러 개일 때 대표 결과를 고른다(#87). 운영에서 같은 영상을 다시 넣을 때마다 새로 분석해
 * 장소를 또 저장해서(김해 5번, 사당 3번) 영상 기록에 같은 영상이 여러 줄로 보였다.
 * 대표는 "장소가 남아 있는 결과 중 가장 최근(작업 번호가 가장 큰)" 결과다 — 최신 분석 품질이 반영된 결과를 보여준다.
 */
final class VideoResults {

    private VideoResults() {
    }

    /** 영상(정식 주소) → 대표 결과의 작업 번호. 장소 목록에 한 번도 안 나온 작업(장소 0곳)은 대표가 될 수 없다. */
    static Map<String, Long> latestJobIdByVideo(List<SavedPlace> places) {
        Map<String, Long> latest = new HashMap<>();
        for (SavedPlace place : places) {
            latest.merge(
                    ShareUrl.videoKey(place.getProcessingJob().getSourceUrl()),
                    place.getProcessingJob().getId(),
                    Math::max);
        }
        return latest;
    }
}
