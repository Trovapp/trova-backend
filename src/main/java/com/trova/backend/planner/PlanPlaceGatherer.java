package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.replan.GeoUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 고른 영상들의 장소를 모으고, 시작 전에 물어봐야 할지 판단한다(#106).
 * 영상끼리 장소 중심이 100km 넘게 떨어지면 하루 동선으로 묶기 어려워 먼저 묻는다(2026-10-03 사용자 승인).
 */
public final class PlanPlaceGatherer {

    static final double FAR_APART_KM = 100;

    private PlanPlaceGatherer() {
    }

    public record VideoPlaces(Long jobId, String title, List<SavedPlace> places) {
    }

    public record Gathered(List<VideoPlaces> videos, int totalPlaces, int placesWithCoords,
                           double maxVideoDistanceKm, Optional<String> question) {
    }

    public static Gathered gather(List<VideoPlaces> videos) {
        int total = videos.stream().mapToInt(v -> v.places().size()).sum();
        int withCoords = (int) videos.stream().flatMap(v -> v.places().stream())
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null).count();
        List<double[]> centers = new ArrayList<>();
        for (VideoPlaces v : videos) {
            centroid(v.places()).ifPresent(centers::add);
        }
        double maxKm = 0;
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                maxKm = Math.max(maxKm, GeoUtils.haversineKm(centers.get(i)[0], centers.get(i)[1],
                        centers.get(j)[0], centers.get(j)[1]));
            }
        }
        Optional<String> question = maxKm > FAR_APART_KM
                ? Optional.of("영상 속 지역이 서로 %dkm 넘게 떨어져 있어요. 지역별로 날을 나눌까요, 한 지역만 갈까요?"
                .formatted(Math.round(maxKm)))
                : Optional.empty();
        return new Gathered(videos, total, withCoords, Math.round(maxKm * 10) / 10.0, question);
    }

    static Optional<double[]> centroid(List<SavedPlace> places) {
        List<SavedPlace> located = places.stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null).toList();
        if (located.isEmpty()) {
            return Optional.empty();
        }
        double lat = located.stream().mapToDouble(SavedPlace::getLatitude).average().orElse(0);
        double lng = located.stream().mapToDouble(SavedPlace::getLongitude).average().orElse(0);
        return Optional.of(new double[]{lat, lng});
    }
}
