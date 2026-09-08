package com.trova.backend.service;

import com.trova.backend.entity.SavedPlace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

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
        return optimize(places, SavedPlace::getLatitude, SavedPlace::getLongitude);
    }

    public static double totalDistanceKm(List<SavedPlace> places) {
        return totalDistanceKm(places, SavedPlace::getLatitude, SavedPlace::getLongitude);
    }

    /**
     * SavedPlace뿐 아니라 위/경도를 가진 어떤 엔티티(예: TripPlace)에도 쓸 수 있도록
     * 좌표 접근자를 받는 범용 버전 — 알고리즘은 위 SavedPlace 전용 메서드와 동일하며,
     * 그 메서드들이 내부적으로 이 버전에 위임한다(로직 중복 없음).
     */
    public static <T> List<T> optimize(List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf) {
        List<T> withCoords = places.stream().filter(p -> hasCoordinates(p, latOf, lngOf)).toList();
        List<T> withoutCoords = places.stream().filter(p -> !hasCoordinates(p, latOf, lngOf)).toList();

        List<T> orderedWithCoords;
        if (withCoords.size() <= 1) {
            orderedWithCoords = withCoords;
        } else if (withCoords.size() <= EXACT_SEARCH_MAX_SIZE) {
            orderedWithCoords = shortestPathExact(withCoords, latOf, lngOf);
        } else {
            orderedWithCoords = shortestPathNearestNeighbor(withCoords, latOf, lngOf);
        }

        List<T> result = new ArrayList<>(orderedWithCoords);
        result.addAll(withoutCoords);
        return result;
    }

    public static <T> double totalDistanceKm(List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf) {
        double total = 0.0;
        for (int i = 0; i < places.size() - 1; i++) {
            T from = places.get(i);
            T to = places.get(i + 1);
            if (hasCoordinates(from, latOf, lngOf) && hasCoordinates(to, latOf, lngOf)) {
                total += haversineKm(latOf.apply(from), lngOf.apply(from), latOf.apply(to), lngOf.apply(to));
            }
        }
        return total;
    }

    private static <T> boolean hasCoordinates(T place, Function<T, Double> latOf, Function<T, Double> lngOf) {
        return latOf.apply(place) != null && lngOf.apply(place) != null;
    }

    /** 모든 순열 × 모든 시작점을 탐색해 총 이동거리가 최소인 경로(왕복 아님, 편도)를 찾는다. */
    private static <T> List<T> shortestPathExact(List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf) {
        List<T> working = new ArrayList<>(places);
        double[] bestDistanceHolder = {totalDistanceKm(places, latOf, lngOf)};
        List<List<T>> bestPermutationHolder = new ArrayList<>();
        bestPermutationHolder.add(places);

        permute(working, 0, bestDistanceHolder, bestPermutationHolder, latOf, lngOf);
        return bestPermutationHolder.get(0);
    }

    private static <T> void permute(
            List<T> arr, int k, double[] bestDistanceHolder, List<List<T>> bestPermutationHolder,
            Function<T, Double> latOf, Function<T, Double> lngOf
    ) {
        if (k == arr.size()) {
            double distance = totalDistanceKm(arr, latOf, lngOf);
            if (distance < bestDistanceHolder[0]) {
                bestDistanceHolder[0] = distance;
                bestPermutationHolder.set(0, new ArrayList<>(arr));
            }
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            Collections.swap(arr, k, i);
            permute(arr, k + 1, bestDistanceHolder, bestPermutationHolder, latOf, lngOf);
            Collections.swap(arr, k, i);
        }
    }

    /** 가장 가까운 미방문 지점을 계속 골라 잇는 근사 알고리즘. 첫 지점은 입력 순서의 첫 장소로 고정. */
    private static <T> List<T> shortestPathNearestNeighbor(
            List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf
    ) {
        List<T> remaining = new ArrayList<>(places);
        List<T> ordered = new ArrayList<>();

        T current = remaining.remove(0);
        ordered.add(current);

        while (!remaining.isEmpty()) {
            T nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (T candidate : remaining) {
                double d = haversineKm(
                        latOf.apply(current), lngOf.apply(current),
                        latOf.apply(candidate), lngOf.apply(candidate));
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
