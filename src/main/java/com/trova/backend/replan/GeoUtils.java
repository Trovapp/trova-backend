package com.trova.backend.replan;

/**
 * 일정 재구성의 이동시간 충돌 판정에 쓰는 순수 지리 계산 유틸. 새 후보와 이웃
 * 장소 사이의 이동시간을 추정한다.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;
    // 재구성 요청은 이동 수단을 받지 않는다(v1은 indoorOnly만 지원) — 가장 보수적인
    // (느린) 도보 속도를 기본값으로 써서, 실제보다 이동시간을 더 길게 잡아 충돌을
    // 과소가 아닌 과대평가하는 쪽으로 안전하게 치우친다. AlternativeFinderService의
    // AVERAGE_SPEED_KMH의 WALK 값(4.0)과 동일하게 맞췄다.
    private static final double WALK_SPEED_KMH = 4.0;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    public static int estimatedWalkMinutes(double lat1, double lng1, double lat2, double lng2) {
        double km = haversineKm(lat1, lng1, lat2, lng2);
        return (int) Math.round(km / WALK_SPEED_KMH * 60);
    }
}
