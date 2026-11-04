package com.trova.backend.service;

import com.trova.backend.service.RouteOptimizerEquivalenceTest.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/**
 * #313 전후 시간 측정. 평소 빌드에서는 돌지 않고 ROUTE_BENCH=1일 때만 실행한다.
 * 같은 입력에서 예전(전체 순열)·새(동적 계획법)를 번갈아 순서를 바꿔 재고, 모든 표본을 ROUTE_BENCH_OUT JSON에 남긴다.
 */
@EnabledIfEnvironmentVariable(named = "ROUTE_BENCH", matches = "1")
class RouteOptimizerBenchTest {

    private static final int INPUTS_PER_CASE = 3;
    private static final int WARMUP = 2;
    private static final int MEASURED = 5;

    private record Case(String name, int size, boolean keepingFixed) {
    }

    @Test
    void 예전과_새_방식의_실행_시간을_같은_입력으로_잰다() throws IOException {
        List<Case> cases = new ArrayList<>();
        for (int size = 6; size <= 10; size++) {
            cases.add(new Case("optimize", size, false));
        }
        cases.add(new Case("optimizeKeepingFixed", 10, true));

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"issue\": 313,\n");
        json.append("  \"measuredAt\": \"").append(OffsetDateTime.now()).append("\",\n");
        json.append("  \"environment\": {\"os\": \"").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append("\", \"java\": \"").append(System.getProperty("java.version"))
                .append("\", \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors())
                .append(", \"note\": \"로컬 측정(ROUTE_BENCH_ENV로 지정). 운영 서버 값이 아니면 운영 수치로 쓰지 않는다\", \"label\": \"")
                .append(System.getenv().getOrDefault("ROUTE_BENCH_ENV", "local")).append("\"},\n");
        json.append("  \"method\": {\"inputsPerCase\": ").append(INPUTS_PER_CASE).append(", \"warmupPerImpl\": ").append(WARMUP)
                .append(", \"measuredPerImpl\": ").append(MEASURED)
                .append(", \"order\": \"짝수 회차는 예전→새, 홀수 회차는 새→예전\", \"seed\": 313},\n");
        json.append("  \"cases\": [\n");

        Random random = new Random(313);
        for (int c = 0; c < cases.size(); c++) {
            Case benchCase = cases.get(c);
            List<long[]> legacySamples = new ArrayList<>();
            List<long[]> currentSamples = new ArrayList<>();
            for (int input = 0; input < INPUTS_PER_CASE; input++) {
                List<Point> points = input(random, benchCase);
                Supplier<List<Point>> legacy = benchCase.keepingFixed()
                        ? () -> LegacyRouteOptimizer.optimizeKeepingFixed(points, Point::fixed, Point::lat, Point::lng)
                        : () -> LegacyRouteOptimizer.optimize(points, Point::lat, Point::lng);
                Supplier<List<Point>> current = benchCase.keepingFixed()
                        ? () -> RouteOptimizer.optimizeKeepingFixed(points, Point::fixed, Point::lat, Point::lng)
                        : () -> RouteOptimizer.optimize(points, Point::lat, Point::lng);
                for (int w = 0; w < WARMUP; w++) {
                    legacy.get();
                    current.get();
                }
                long[] legacyNs = new long[MEASURED];
                long[] currentNs = new long[MEASURED];
                for (int run = 0; run < MEASURED; run++) {
                    if (run % 2 == 0) {
                        legacyNs[run] = time(legacy);
                        currentNs[run] = time(current);
                    } else {
                        currentNs[run] = time(current);
                        legacyNs[run] = time(legacy);
                    }
                }
                legacySamples.add(legacyNs);
                currentSamples.add(currentNs);
            }
            json.append("    {\"case\": \"").append(benchCase.name()).append("\", \"movablePlaces\": ").append(benchCase.size())
                    .append(",\n      \"legacyNs\": ").append(arrays(legacySamples))
                    .append(",\n      \"currentNs\": ").append(arrays(currentSamples))
                    .append(",\n      \"legacyMedianMs\": ").append(ms(median(legacySamples)))
                    .append(", \"currentMedianMs\": ").append(ms(median(currentSamples)))
                    .append(", \"legacyMeanMs\": ").append(ms(mean(legacySamples)))
                    .append(", \"currentMeanMs\": ").append(ms(mean(currentSamples)))
                    .append("}").append(c < cases.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");

        String out = System.getenv("ROUTE_BENCH_OUT");
        if (out != null) {
            Files.writeString(Path.of(out), json.toString(), StandardCharsets.UTF_8);
        }
        System.out.println(json);
    }

    private static List<Point> input(Random random, Case benchCase) {
        List<Point> points = RouteOptimizerEquivalenceTest.randomPoints(random, benchCase.size(), 0.0, 0.0);
        if (benchCase.keepingFixed()) {
            points.add(4, new Point("점심", 37.55, 127.0, true));
            points.add(new Point("숙소", 37.50, 126.95, true));
        }
        return points;
    }

    private static long time(Supplier<List<Point>> run) {
        long started = System.nanoTime();
        List<Point> result = run.get();
        long elapsed = System.nanoTime() - started;
        if (result.isEmpty()) {
            throw new IllegalStateException("빈 결과");
        }
        return elapsed;
    }

    private static long[] flat(List<long[]> samples) {
        return samples.stream().flatMapToLong(Arrays::stream).sorted().toArray();
    }

    private static double median(List<long[]> samples) {
        long[] all = flat(samples);
        int mid = all.length / 2;
        return all.length % 2 == 1 ? all[mid] : (all[mid - 1] + all[mid]) / 2.0;
    }

    private static double mean(List<long[]> samples) {
        return Arrays.stream(flat(samples)).average().orElse(0);
    }

    private static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }

    private static String arrays(List<long[]> samples) {
        List<String> rows = new ArrayList<>();
        for (long[] row : samples) {
            rows.add(Arrays.toString(row));
        }
        return "[" + String.join(", ", rows) + "]";
    }
}
