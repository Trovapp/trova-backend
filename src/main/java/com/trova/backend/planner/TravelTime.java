package com.trova.backend.planner;

import com.trova.backend.replan.GeoUtils;

/**
 * 장소 사이 이동 시간 어림: 직선거리를 시속 30km로 나눠 10분 단위로 올리고, 최소 10분.
 * 좌표가 없으면 30분. 일정 초안(DraftFixer)과 여행 순서 변경 뒤 시간 다시 매기기(TripDayRetimer)가 같은 규칙을 쓴다.
 */
public final class TravelTime {

    private TravelTime() {
    }

    public static long minutes(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return 30;
        }
        double km = GeoUtils.haversineKm(lat1, lng1, lat2, lng2);
        long minutes = (long) Math.ceil(km / 30.0 * 60 / 10.0) * 10;
        return Math.max(10, minutes);
    }
}
