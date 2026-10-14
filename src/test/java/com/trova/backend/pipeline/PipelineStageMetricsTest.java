package com.trova.backend.pipeline;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineStageMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineStageMetrics metrics = new PipelineStageMetrics(registry);

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
