package com.trova.backend.service;

import com.trova.backend.entity.SavedPlace;

import java.util.ArrayList;
import java.util.List;

/**
 * 하루 일정 안에서 총 이동거리가 최소가 되도록 장소 순서를 재배열한다. 구글/카카오
 * Distance Matrix 없이, 이미 지오코딩된 좌표로 직선거리(haversine)만 써서 계산한다
 * — 외부 API 호출 없이 0원으로 동작.
 *
 * 좌표 없는 장소는 거리 계산이 불가능하므로 최적화 대상에서 빼고, 원래 상대 순서를
 * 유지한 채 맨 뒤에 그대로 붙인다.
 */
public final class RouteOptimizer {

    // 순열을 전부 탐색하는 정확해(brute force) 방식은 n!로 늘어난다. 하루 방문지가
    // 이 개수를 넘으면 대신 nearest-neighbor 근사로 전환한다.
    // 10! = 3,628,800 — 로컬에서 수백 ms 안에 끝나는 안전한 상한선으로 잡음(실측 아님, 이론적 상한).
    private static final int EXACT_SEARCH_MAX_SIZE = 10;

    private RouteOptimizer() {
    }

    public static List<SavedPlace> optimize(List<SavedPlace> places) {
        List<SavedPlace> withCoords = places.stream().filter(RouteOptimizer::hasCoordinates).toList();
        List<SavedPlace> withoutCoords = places.stream().filter(p -> !hasCoordinates(p)).toList();

        List<SavedPlace> orderedWithCoords;
        if (withCoords.size() <= 1) {
            orderedWithCoords = withCoords;
        } else if (withCoords.size() <= EXACT_SEARCH_MAX_SIZE) {
            orderedWithCoords = shortestPathExact(withCoords);
        } else {
            orderedWithCoords = shortestPathNearestNeighbor(withCoords);
        }

        List<SavedPlace> result = new ArrayList<>(orderedWithCoords);
        result.addAll(withoutCoords);
        return result;
    }

    public static double totalDistanceKm(List<SavedPlace> places) {
        double total = 0.0;
        for (int i = 0; i < places.size() - 1; i++) {
            SavedPlace from = places.get(i);
            SavedPlace to = places.get(i + 1);
            if (hasCoordinates(from) && hasCoordinates(to)) {
                total += haversineKm(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
            }
        }
        return total;
    }

    private static boolean hasCoordinates(SavedPlace place) {
        return place.getLatitude() != null && place.getLongitude() != null;
    }

    /** 모든 순열 × 모든 시작점을 탐색해 총 이동거리가 최소인 경로(왕복 아님, 편도)를 찾는다. */
    private static List<SavedPlace> shortestPathExact(List<SavedPlace> places) {
        List<SavedPlace> working = new ArrayList<>(places);
        double[] bestDistanceHolder = {totalDistanceKm(places)};
        List<List<SavedPlace>> bestPermutationHolder = new ArrayList<>();
        bestPermutationHolder.add(places);

        permute(working, 0, bestDistanceHolder, bestPermutationHolder);
        return bestPermutationHolder.get(0);
    }

    private static void permute(
            List<SavedPlace> arr, int k, double[] bestDistanceHolder, List<List<SavedPlace>> bestPermutationHolder
    ) {
        if (k == arr.size()) {
            double distance = totalDistanceKm(arr);
            if (distance < bestDistanceHolder[0]) {
                bestDistanceHolder[0] = distance;
                bestPermutationHolder.set(0, new ArrayList<>(arr));
            }
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            java.util.Collections.swap(arr, k, i);
            permute(arr, k + 1, bestDistanceHolder, bestPermutationHolder);
            java.util.Collections.swap(arr, k, i);
        }
    }

    /** 가장 가까운 미방문 지점을 계속 골라 잇는 근사 알고리즘. 첫 지점은 입력 순서의 첫 장소로 고정. */
    private static List<SavedPlace> shortestPathNearestNeighbor(List<SavedPlace> places) {
        List<SavedPlace> remaining = new ArrayList<>(places);
        List<SavedPlace> ordered = new ArrayList<>();

        SavedPlace current = remaining.remove(0);
        ordered.add(current);

        while (!remaining.isEmpty()) {
            SavedPlace nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (SavedPlace candidate : remaining) {
                double d = haversineKm(
                        current.getLatitude(), current.getLongitude(),
                        candidate.getLatitude(), candidate.getLongitude());
                if (d < nearestDistance) {
                    nearestDistance = d;
                    nearest = candidate;
                }
            }
            ordered.add(nearest);
            remaining.remove(nearest);
            current = nearest;
        }

        return ordered;
    }

    private static final double EARTH_RADIUS_KM = 6371.0;

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
