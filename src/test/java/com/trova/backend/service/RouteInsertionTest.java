package com.trova.backend.service;

import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TripPlace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteInsertionTest {

    private static TripPlace place(String name, Double lat, Double lng) {
        return new TripPlace(null, name, null, null, lat, lng, null, null, 1, PlaceSource.NORMAL, null);
    }

    @Test
    void 두_장소_사이에_있으면_그_사이에_넣는다() {
        List<TripPlace> day = List.of(place("남", 37.50, 127.0), place("북", 37.70, 127.0));

        assertThat(RouteInsertion.bestIndex(day, 37.60, 127.0)).isEqualTo(1);
    }

    @Test
    void 첫_장소보다_더_바깥이면_맨_앞에_넣는다() {
        List<TripPlace> day = List.of(place("가운데", 37.60, 127.0), place("북", 37.70, 127.0));

        assertThat(RouteInsertion.bestIndex(day, 37.40, 127.0)).isEqualTo(0);
    }

    @Test
    void 마지막_장소보다_더_바깥이면_맨_뒤에_넣는다() {
        List<TripPlace> day = List.of(place("남", 37.50, 127.0), place("가운데", 37.60, 127.0));

        assertThat(RouteInsertion.bestIndex(day, 37.80, 127.0)).isEqualTo(2);
    }

    @Test
    void 좌표가_없으면_맨_뒤에_넣는다() {
        List<TripPlace> day = List.of(place("남", 37.50, 127.0), place("북", 37.70, 127.0));

        assertThat(RouteInsertion.bestIndex(day, null, null)).isEqualTo(2);
    }

    @Test
    void 좌표_없는_장소_옆은_거리를_잴_수_없어_건너뛴다() {
        // 좌표 없는 장소는 동선 최적화에서도 맨 뒤로 가는 장소라, 그 앞뒤에 끼워 넣지 않는다
        List<TripPlace> day = List.of(place("남", 37.50, 127.0), place("북", 37.70, 127.0), place("모름", null, null));

        assertThat(RouteInsertion.bestIndex(day, 37.80, 127.0)).isEqualTo(2);
    }

    @Test
    void 빈_날이면_0() {
        assertThat(RouteInsertion.bestIndex(List.of(), 37.5, 127.0)).isEqualTo(0);
    }
}
