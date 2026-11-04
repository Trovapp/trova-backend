package com.trova.backend.service;

import com.trova.backend.entity.SavedPlace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 하루 일정 안에서 총 이동거리가 최소가 되도록 장소 순서를 재배열한다. 구글/카카오
 * Distance Matrix 없이, 이미 지오코딩된 좌표로 직선거리(haversine)만 써서 계산한다
 * — 외부 API 호출 없이 0원으로 동작.
 *
 * 좌표 없는 장소는 거리 계산이 불가능하므로 최적화 대상에서 빼고, 원래 상대 순서를
 * 유지한 채 맨 뒤에 그대로 붙인다.
 */
public final class RouteOptimizer {

    // 이 개수 이하면 정확해, 넘으면 nearest-neighbor 근사. 정확해는 예전엔 모든 순서(10! = 3,628,800)를 따졌고,
    // #313부터 같은 최적값을 동적 계획법(Held-Karp, n²·2ⁿ ≈ 10만 번)으로 구한다. 상한은 동작을 바꾸지 않으려고 그대로 뒀다.
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

    /**
     * 고정 장소(예: 식당)와 좌표 없는 장소는 제자리에 두고, 나머지 자리만 채워 전체 경로를 가장 짧게 만든다.
     * 여행 동선 최적화가 거리만 보고 식당을 옮겨 점심 식당이 아침 첫 순서가 됐다(#179) — 일정 초안(DraftFixer.reorder)과 같은 원칙.
     * 남은 자리가 EXACT_SEARCH_MAX_SIZE 이하면 모든 순서를 따지고, 넘으면 앞 장소에서 가장 가까운 곳을 차례로 채운다.
     */
    public static <T> List<T> optimizeKeepingFixed(
            List<T> places, Predicate<T> fixed, Function<T, Double> latOf, Function<T, Double> lngOf
    ) {
        List<Integer> slots = new ArrayList<>();
        List<T> movable = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            T place = places.get(i);
            if (!fixed.test(place) && hasCoordinates(place, latOf, lngOf)) {
                slots.add(i);
                movable.add(place);
            }
        }
        if (movable.size() <= 1) {
            return new ArrayList<>(places);
        }
        if (movable.size() > EXACT_SEARCH_MAX_SIZE) {
            return fillNearestKeepingFixed(places, slots, movable, latOf, lngOf);
        }
        List<T> ordered = fillSlotsExact(places, slots, movable, latOf, lngOf);
        // 예전 방식처럼 원래 순서보다 확실히 짧을 때만 바꾼다(같으면 원래 순서 유지).
        if (totalDistanceKm(ordered, latOf, lngOf) < totalDistanceKm(places, latOf, lngOf)) {
            return ordered;
        }
        return new ArrayList<>(places);
    }

    /**
     * 빈자리(slots)를 앞에서부터 하나씩 채우는 동적 계획법. 상태는 (지금까지 쓴 장소 집합, 마지막에 넣은 장소)이고,
     * 고정 장소는 자리가 바뀌지 않으므로 빈자리 사이 구간의 거리는 "앞 빈자리 장소 → 고정 구간 첫 장소"와
     * "고정 구간 마지막 장소 → 다음 빈자리 장소" 두 조각만 장소에 따라 달라진다.
     */
    private static <T> List<T> fillSlotsExact(
            List<T> places, List<Integer> slots, List<T> movable, Function<T, Double> latOf, Function<T, Double> lngOf
    ) {
        int m = movable.size();
        int lastSlot = slots.get(m - 1);
        double[][] between = distanceMatrix(movable, latOf, lngOf);
        double[] enterFirst = new double[m];
        double[] exitLast = new double[m];
        double[][] leave = new double[m][m];
        double[][] enter = new double[m][m];
        for (int j = 0; j < m; j++) {
            T place = movable.get(j);
            int first = slots.get(0);
            enterFirst[j] = first > 0 ? pairKm(places.get(first - 1), place, latOf, lngOf) : 0.0;
            exitLast[j] = lastSlot + 1 < places.size() ? pairKm(place, places.get(lastSlot + 1), latOf, lngOf) : 0.0;
            for (int t = 1; t < m; t++) {
                int prevSlot = slots.get(t - 1);
                int slot = slots.get(t);
                if (slot > prevSlot + 1) {
                    leave[t][j] = pairKm(place, places.get(prevSlot + 1), latOf, lngOf);
                    enter[t][j] = pairKm(places.get(slot - 1), place, latOf, lngOf);
                }
            }
        }

        int full = (1 << m) - 1;
        double[][] best = new double[full + 1][m];
        int[][] parent = new int[full + 1][m];
        for (double[] row : best) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int j = 0; j < m; j++) {
            best[1 << j][j] = enterFirst[j];
            parent[1 << j][j] = -1;
        }
        for (int mask = 1; mask <= full; mask++) {
            int t = Integer.bitCount(mask);
            if (t == m) {
                continue;
            }
            boolean adjacent = slots.get(t) == slots.get(t - 1) + 1;
            for (int j = 0; j < m; j++) {
                double base = best[mask][j];
                if (base == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int k = 0; k < m; k++) {
                    if ((mask & (1 << k)) != 0) {
                        continue;
                    }
                    double step = adjacent ? between[j][k] : leave[t][j] + enter[t][k];
                    int next = mask | (1 << k);
                    if (base + step < best[next][k]) {
                        best[next][k] = base + step;
                        parent[next][k] = j;
                    }
                }
            }
        }
        int[] order = reconstruct(best[full], exitLast, parent, full, m);
        List<T> result = new ArrayList<>(places);
        for (int t = 0; t < m; t++) {
            result.set(slots.get(t), movable.get(order[t]));
        }
        return result;
    }

    private static <T> List<T> fillNearestKeepingFixed(
            List<T> places, List<Integer> slots, List<T> movable, Function<T, Double> latOf, Function<T, Double> lngOf
    ) {
        List<T> remaining = new ArrayList<>(movable);
        List<T> result = new ArrayList<>(places);
        for (int slot : slots) {
            T prev = slot > 0 ? result.get(slot - 1) : null;
            T pick = remaining.get(0);
            if (prev != null && hasCoordinates(prev, latOf, lngOf)) {
                double nearest = Double.MAX_VALUE;
                for (T candidate : remaining) {
                    double d = haversineKm(latOf.apply(prev), lngOf.apply(prev), latOf.apply(candidate), lngOf.apply(candidate));
                    if (d < nearest) {
                        nearest = d;
                        pick = candidate;
                    }
                }
            }
            remaining.remove(pick);
            result.set(slot, pick);
        }
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

    /**
     * 총 이동거리가 최소인 편도 경로(시작점 자유)를 동적 계획법(Held-Karp)으로 구한다. 예전 전체 순열 탐색과 같은 최적값이며,
     * 예전처럼 원래 순서보다 확실히 짧을 때만 순서를 바꾼다. 거리가 똑같은 경로가 여러 개면 어느 쪽을 고르는지는 예전과 다를 수 있다.
     */
    private static <T> List<T> shortestPathExact(List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf) {
        int n = places.size();
        double[][] distance = distanceMatrix(places, latOf, lngOf);
        int full = (1 << n) - 1;
        double[][] best = new double[full + 1][n];
        int[][] parent = new int[full + 1][n];
        for (double[] row : best) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int j = 0; j < n; j++) {
            best[1 << j][j] = 0.0;
            parent[1 << j][j] = -1;
        }
        for (int mask = 1; mask < full; mask++) {
            for (int j = 0; j < n; j++) {
                double base = best[mask][j];
                if (base == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int k = 0; k < n; k++) {
                    if ((mask & (1 << k)) != 0) {
                        continue;
                    }
                    int next = mask | (1 << k);
                    if (base + distance[j][k] < best[next][k]) {
                        best[next][k] = base + distance[j][k];
                        parent[next][k] = j;
                    }
                }
            }
        }
        int[] order = reconstruct(best[full], new double[n], parent, full, n);
        List<T> path = new ArrayList<>(n);
        for (int index : order) {
            path.add(places.get(index));
        }
        return totalDistanceKm(path, latOf, lngOf) < totalDistanceKm(places, latOf, lngOf) ? path : places;
    }

    /** 마지막 장소별 최소 거리(+끝 비용)에서 가장 짧은 것을 고르고 parent를 거슬러 순서를 만든다. */
    private static int[] reconstruct(double[] lastRow, double[] endCost, int[][] parent, int full, int size) {
        int last = 0;
        for (int j = 1; j < size; j++) {
            if (lastRow[j] + endCost[j] < lastRow[last] + endCost[last]) {
                last = j;
            }
        }
        int[] order = new int[size];
        int mask = full;
        for (int t = size - 1; t >= 0; t--) {
            order[t] = last;
            int previous = parent[mask][last];
            mask &= ~(1 << last);
            last = previous;
        }
        return order;
    }

    /** 장소 쌍 거리를 한 번씩만 계산해 둔다. 방향마다 계산해 예전 합계와 같은 값을 쓴다. */
    private static <T> double[][] distanceMatrix(List<T> places, Function<T, Double> latOf, Function<T, Double> lngOf) {
        int n = places.size();
        double[][] distance = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    distance[i][j] = pairKm(places.get(i), places.get(j), latOf, lngOf);
                }
            }
        }
        return distance;
    }

    /** totalDistanceKm과 같은 규칙: 둘 중 하나라도 좌표가 없으면 0. */
    private static <T> double pairKm(T from, T to, Function<T, Double> latOf, Function<T, Double> lngOf) {
        if (!hasCoordinates(from, latOf, lngOf) || !hasCoordinates(to, latOf, lngOf)) {
            return 0.0;
        }
        return haversineKm(latOf.apply(from), lngOf.apply(from), latOf.apply(to), lngOf.apply(to));
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
