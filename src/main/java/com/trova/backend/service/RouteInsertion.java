package com.trova.backend.service;

import com.trova.backend.entity.TripPlace;
import com.trova.backend.replan.GeoUtils;

import java.util.List;

/**
 * 하루 일정에 장소 하나를 넣을 자리를 고른다 — 앞뒤 장소 사이의 직선거리가 가장 적게 늘어나는 자리(#221).
 * 일정 초안에서 뺀 장소를 찾아 넣으면 그날 맨 끝에 붙어 동선이 되돌아갔다(2026-10-18 안드로이드 QA).
 * 좌표 없는 장소 뒤에는 넣지 않는다 — 거리를 잴 수 없고, 동선 최적화에서도 맨 뒤로 가는 장소다.
 */
final class RouteInsertion {

    private RouteInsertion() {
    }

    /** 넣을 자리(0이면 맨 앞, ordered.size()면 맨 뒤). 새 장소 좌표가 없으면 맨 뒤. */
    static int bestIndex(List<TripPlace> ordered, Double lat, Double lng) {
        if (lat == null || lng == null) {
            return ordered.size();
        }
        int best = ordered.size();
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i <= ordered.size(); i++) {
            TripPlace prev = i > 0 ? ordered.get(i - 1) : null;
            TripPlace next = i < ordered.size() ? ordered.get(i) : null;
            if (prev != null && !hasCoords(prev)) {
                continue;
            }
            if (next != null && !hasCoords(next)) {
                next = null;
            }
            double cost = distance(prev, lat, lng) + distance(next, lat, lng);
            if (prev != null && next != null) {
                cost -= GeoUtils.haversineKm(prev.getLatitude(), prev.getLongitude(), next.getLatitude(), next.getLongitude());
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    private static boolean hasCoords(TripPlace place) {
        return place.getLatitude() != null && place.getLongitude() != null;
    }

    private static double distance(TripPlace place, double lat, double lng) {
        return place == null ? 0 : GeoUtils.haversineKm(place.getLatitude(), place.getLongitude(), lat, lng);
    }
}
