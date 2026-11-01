package com.trova.backend.pipeline;

import com.trova.backend.entity.ProcessingStage;
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
}
