package com.trova.backend.pipeline;

import com.trova.backend.entity.ProcessingStage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineStageMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineStageMetrics metrics = new PipelineStageMetrics(registry);

    @Test
    void 단계_합이_실행_시간과_같고_완료_뒤_초안_시간은_제외한다() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry timedRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        PipelineStageMetrics timedMetrics = new PipelineStageMetrics(timedRegistry);
        PipelineStageMetrics.AnalysisTiming timing = timedMetrics.startAnalysis(1L);
        clock.add(Duration.ofMillis(100));
        timing.stage(ProcessingStage.SELECTING);
        clock.add(Duration.ofSeconds(2));
        timing.stage(ProcessingStage.VERIFYING);
        clock.add(Duration.ofSeconds(3));
        timing.stage(ProcessingStage.SAVING);
        clock.add(Duration.ofSeconds(4));
        timing.finish(PipelineStageMetrics.Outcome.SUCCESS);
        clock.add(Duration.ofSeconds(30));
        timing.close();

        Timer total = timedRegistry.get("trova.analysis.execution").tag("outcome", "success").timer();
        assertThat(total.count()).isEqualTo(1);
        assertThat(total.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(9100);
        assertThat(timedRegistry.find("trova.analysis.stage").tag("outcome", "success").timers()
                .stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum()).isEqualTo(9100);
        assertThat(timedRegistry.get("trova.analysis.stage").tags("stage", "saving", "outcome", "success")
                .timer().totalTime(TimeUnit.SECONDS)).isEqualTo(4);
        assertThat(timedRegistry.get("trova.analysis.execution").tag("outcome", "failure").timer().count()).isZero();
    }

    @Test
    void 끝나지_못한_단계를_닫으면_실패_시간을_한번만_남긴다() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry timedRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        PipelineStageMetrics.AnalysisTiming timing = new PipelineStageMetrics(timedRegistry).startAnalysis(2L);
        timing.stage(ProcessingStage.SELECTING);
        clock.add(Duration.ofSeconds(5));
        timing.close();
        timing.close();

        Timer failed = timedRegistry.get("trova.analysis.stage").tags("stage", "selecting", "outcome", "failure").timer();
        assertThat(failed.count()).isEqualTo(1);
        assertThat(failed.totalTime(TimeUnit.SECONDS)).isEqualTo(5);
        assertThat(timedRegistry.get("trova.analysis.execution").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(timedRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag -> tag.getKey().equals("jobId")));
    }

    @Test
    void 단계마다_시간을_지표로_남긴다() {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("download", 28100L);
        timings.put("gemini", 10600L);

        String line = metrics.record(75L, timings);

        Timer download = registry.find("trova.pipeline.stage").tag("stage", "download").timer();
        assertThat(download).isNotNull();
        assertThat(download.count()).isEqualTo(1);
        assertThat(download.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(28100);
        assertThat(line).isEqualTo("download 28.1초 · gemini 10.6초");
    }

    @Test
    void 시간이_없으면_아무것도_남기지_않는다() {
        assertThat(metrics.record(75L, Map.of())).isNull();
        assertThat(registry.find("trova.pipeline.stage").timers()).isEmpty();
    }

    @Test
    void 알_수_없는_단계_이름이나_음수는_건너뛴다() {
        // 지표 태그가 무한히 늘지 않게 정한 이름만 받는다.
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("download", -5L);
        timings.put("whatever", 100L);
        timings.put("audio", 400L);

        assertThat(metrics.record(1L, timings)).isEqualTo("audio 0.4초");
        assertThat(registry.find("trova.pipeline.stage").timers()).hasSize(1);
    }

    @Test
    void Python_자원을_지표와_로그_한_줄로_남긴다() {
        Map<String, Long> resources = new LinkedHashMap<>();
        resources.put("cpuUserMs", 300L);
        resources.put("cpuSystemMs", 50L);
        resources.put("childCpuUserMs", 900L);
        resources.put("childCpuSystemMs", 100L);
        resources.put("maxRssKib", 40960L);
        resources.put("childMaxRssKib", 81920L);
        resources.put("workerWallMs", 11800L);
        resources.put("processWallMs", 12100L);
        resources.put("buildPartsUs", 2500L);
        resources.put("requestJsonUs", 1500L);
        resources.put("requestBytes", 2048L);

        String line = metrics.recordResources(7L, resources);

        assertThat(line).isEqualTo("Python CPU 350ms · 자식 CPU 1000ms · Python 최대 메모리 40.0MiB · 가장 큰 자식 80.0MiB"
                + " · 시작·종료 300ms · 요청 parts 2.5ms · 요청 JSON 1.5ms · 요청 2.0KiB");
        DistributionSummary cpu = registry.get("trova.pipeline.worker.cpu").tag("process", "worker").summary();
        assertThat(cpu.totalAmount()).isEqualTo(350);
        assertThat(registry.get("trova.pipeline.worker.max_rss").tag("process", "worker").summary().totalAmount())
                .isEqualTo(40960 * 1024.0);
        assertThat(registry.get("trova.pipeline.worker.overhead").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300);
        assertThat(registry.get("trova.pipeline.request_prep").tag("part", "build_parts").timer()
                .totalTime(TimeUnit.MICROSECONDS)).isEqualTo(2500);
    }

    @Test
    void 자원_값이_없거나_음수면_건너뛴다() {
        assertThat(metrics.recordResources(1L, Map.of())).isNull();
        // 유튜브 링크 직접 분석은 자식 프로세스가 없어 0 — 자식 최대 메모리는 남기지 않는다.
        assertThat(metrics.recordResources(2L, Map.of("cpuUserMs", -1L, "cpuSystemMs", 5L, "childMaxRssKib", 0L,
                "maxRssKib", 1024L))).isEqualTo("Python 최대 메모리 1.0MiB");
        assertThat(registry.find("trova.pipeline.worker.cpu").summaries()).isEmpty();
    }
}
