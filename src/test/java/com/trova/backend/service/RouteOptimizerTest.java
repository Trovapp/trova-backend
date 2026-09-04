package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteOptimizerTest {

    private static final User USER = new User("google", "route-test-user", "테스트유저", null);
    private static final ProcessingJob JOB =
            new ProcessingJob(USER, "https://youtu.be/route-test", SourcePlatform.YOUTUBE);

    private SavedPlace place(String name, Double lat, Double lng) {
        return new SavedPlace(JOB, USER, name, "서울", "cafe", lat, lng, 1, 1);
    }

    @Test
    void 세_지점_중_중간_지점이_가운데로_오도록_재배열한다() {
        // A(37.500)---C(37.501)---B(37.502) — 일직선상, C가 중간.
        // 입력 순서는 A,B,C지만 총 이동거리를 최소화하면 반드시 C가 가운데(index 1)여야 한다.
        SavedPlace a = place("A", 37.500, 127.000);
        SavedPlace b = place("B", 37.502, 127.000);
        SavedPlace c = place("C", 37.501, 127.000);

        List<SavedPlace> result = RouteOptimizer.optimize(List.of(a, b, c));

        assertThat(result).hasSize(3);
        assertThat(result.get(1)).isEqualTo(c);
        assertThat(List.of(result.get(0), result.get(2))).containsExactlyInAnyOrder(a, b);

        double optimizedTotal = RouteOptimizer.totalDistanceKm(result);
        double originalTotal = RouteOptimizer.totalDistanceKm(List.of(a, b, c));
        assertThat(optimizedTotal).isLessThan(originalTotal);
    }

    @Test
    void 좌표_없는_장소는_원래_상대순서를_유지한_채_끝으로_밀린다() {
        SavedPlace a = place("A", 37.500, 127.000);
        SavedPlace b = place("B", 37.502, 127.000);
        SavedPlace noCoord1 = place("이름뿐인곳1", null, null);
        SavedPlace noCoord2 = place("이름뿐인곳2", null, null);

        List<SavedPlace> result = RouteOptimizer.optimize(List.of(noCoord1, a, noCoord2, b));

        assertThat(result).hasSize(4);
        assertThat(result.get(0)).isIn(a, b);
        assertThat(result.get(1)).isIn(a, b);
        assertThat(result.get(2)).isEqualTo(noCoord1);
        assertThat(result.get(3)).isEqualTo(noCoord2);
    }

    @Test
    void 장소가_0개나_1개면_그대로_반환한다() {
        assertThat(RouteOptimizer.optimize(List.of())).isEmpty();

        SavedPlace a = place("A", 37.5, 127.0);
        assertThat(RouteOptimizer.optimize(List.of(a))).containsExactly(a);
    }

    @Test
    void totalDistanceKm은_연속된_두_지점_사이_거리의_합이다() {
        SavedPlace a = place("A", 37.500, 127.000);
        SavedPlace b = place("B", 37.510, 127.000);

        double distance = RouteOptimizer.totalDistanceKm(List.of(a, b));

        // 위도 0.01도 ≈ 1.11km
        assertThat(distance).isCloseTo(1.11, within(0.05));
    }
}
