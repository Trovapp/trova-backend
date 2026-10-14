package com.trova.backend.pipeline;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 분석 단계별 시간을 지표(trova_pipeline_stage_seconds{stage})로 남긴다(#194). 2026-10-14 운영 릴스 추출 41.5초 중
 * Gemini는 10.5초였는데 나머지가 어디인지 로그로는 알 수 없었다. 지표 태그가 늘지 않게 정한 단계 이름만 받는다.
 */
@Component
public class PipelineStageMetrics {

    static final Set<String> STAGES = Set.of("download", "audio", "frames", "gemini");

    private final MeterRegistry registry;

    public PipelineStageMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @return 로그에 붙일 한 줄(예: "download 28.1초 · gemini 10.6초"), 남긴 게 없으면 null */
    public String record(Long jobId, Map<String, Long> timings) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            String stage = entry.getKey();
            Long millis = entry.getValue();
            if (!STAGES.contains(stage) || millis == null || millis < 0) {
                continue;
            }
            Timer.builder("trova.pipeline.stage")
                    .description("영상 분석 단계별 걸린 시간")
                    .tag("stage", stage)
                    .register(registry)
                    .record(Duration.ofMillis(millis));
            parts.add(stage + " " + String.format(Locale.ROOT, "%.1f", millis / 1000.0) + "초");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
