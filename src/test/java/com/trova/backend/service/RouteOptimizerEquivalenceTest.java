package com.trova.backend.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** #313: 동적 계획법이 예전 전체 순열 탐색(LegacyRouteOptimizer)과 같은 최소 거리를 내는지 같은 입력으로 비교한다. */
class RouteOptimizerEquivalenceTest {

    record Point(String name, Double lat, Double lng, boolean fixed) {
    }

    private static final Predicate<Point> FIXED = Point::fixed;

    static List<Point> randomPoints(Random random, int size, double fixedRate, double missingRate) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            boolean missing = random.nextDouble() < missingRate;
            // 서울 근처 약 30km 범위 — 실제 하루 일정 규모
            Double lat = missing ? null : 37.40 + random.nextDouble() * 0.3;
            Double lng = missing ? null : 126.80 + random.nextDouble() * 0.4;
            points.add(new Point("p" + i, lat, lng, random.nextDouble() < fixedRate));
        }
        return points;
    }

    private static double total(List<Point> points) {
        return RouteOptimizer.totalDistanceKm(points, Point::lat, Point::lng);
    }

    @Test
    void 좌표가_있는_장소_2개부터_10개까지_예전_방식과_최소_거리가_같다() {
        Random random = new Random(313);
        for (int size = 2; size <= 10; size++) {
            for (int round = 0; round < (size >= 9 ? 3 : 20); round++) {
                List<Point> points = randomPoints(random, size, 0.0, 0.0);

                List<Point> legacy = LegacyRouteOptimizer.optimize(points, Point::lat, Point::lng);
                List<Point> current = RouteOptimizer.optimize(points, Point::lat, Point::lng);

                assertThat(current).containsExactlyInAnyOrderElementsOf(points);
                assertThat(total(current)).as("size %d round %d", size, round).isCloseTo(total(legacy), within(1e-9));
            }
        }
    }

    @Test
    void 좌표_없는_장소가_섞여도_뒤에_원래_순서로_붙고_최소_거리가_같다() {
        Random random = new Random(3131);
        for (int round = 0; round < 30; round++) {
            List<Point> points = randomPoints(random, 2 + random.nextInt(8), 0.0, 0.25);

            List<Point> legacy = LegacyRouteOptimizer.optimize(points, Point::lat, Point::lng);
            List<Point> current = RouteOptimizer.optimize(points, Point::lat, Point::lng);

            assertThat(total(current)).isCloseTo(total(legacy), within(1e-9));
            List<Point> missing = points.stream().filter(p -> p.lat() == null).toList();
            assertThat(current.subList(current.size() - missing.size(), current.size())).containsExactlyElementsOf(missing);
        }
    }

    @Test
    void 고정_장소와_좌표_없는_장소는_제자리에_두고_최소_거리가_예전과_같다() {
        Random random = new Random(31313);
        for (int round = 0; round < 60; round++) {
            List<Point> points = randomPoints(random, 2 + random.nextInt(10), 0.3, 0.15);

            List<Point> legacy = LegacyRouteOptimizer.optimizeKeepingFixed(points, FIXED, Point::lat, Point::lng);
            List<Point> current = RouteOptimizer.optimizeKeepingFixed(points, FIXED, Point::lat, Point::lng);

            assertThat(total(current)).as("round %d", round).isCloseTo(total(legacy), within(1e-9));
            for (int i = 0; i < points.size(); i++) {
                Point original = points.get(i);
                if (original.fixed() || original.lat() == null) {
                    assertThat(current.get(i)).isSameAs(original);
                }
            }
            assertThat(current).containsExactlyInAnyOrderElementsOf(points);
        }
    }

    @Test
    void 움직일_장소가_10개면_고정_장소_사이로도_예전과_같은_최소_거리를_찾는다() {
        Random random = new Random(313131);
        for (int round = 0; round < 2; round++) {
            List<Point> points = randomPoints(random, 10, 0.0, 0.0);
            points.add(4, new Point("점심", 37.55, 127.0, true));
            points.add(new Point("숙소", 37.50, 126.95, true));

            List<Point> legacy = LegacyRouteOptimizer.optimizeKeepingFixed(points, FIXED, Point::lat, Point::lng);
            List<Point> current = RouteOptimizer.optimizeKeepingFixed(points, FIXED, Point::lat, Point::lng);

            assertThat(total(current)).isCloseTo(total(legacy), within(1e-9));
            assertThat(current.get(4).name()).isEqualTo("점심");
            assertThat(current.get(11).name()).isEqualTo("숙소");
        }
    }

    @Test
    void 이미_가장_짧은_순서면_원래_순서를_그대로_둔다() {
        List<Point> line = List.of(
                new Point("a", 37.50, 127.00, false),
                new Point("b", 37.51, 127.00, false),
                new Point("c", 37.52, 127.00, false));

        assertThat(RouteOptimizer.optimize(line, Point::lat, Point::lng)).containsExactlyElementsOf(line);
        assertThat(RouteOptimizer.optimizeKeepingFixed(line, FIXED, Point::lat, Point::lng)).containsExactlyElementsOf(line);
    }
}
