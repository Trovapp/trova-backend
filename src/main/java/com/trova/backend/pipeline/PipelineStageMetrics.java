package com.trova.backend.pipeline;

import com.trova.backend.entity.ProcessingStage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 분석 단계별 시간을 지표(trova_pipeline_stage_seconds{stage})로 남긴다(#194). 2026-10-14 운영 릴스 추출 41.5초 중
 * Gemini는 10.5초였는데 나머지가 어디인지 로그로는 알 수 없었다. 지표 태그가 늘지 않게 정한 단계 이름만 받는다.
 */
@Component
public class PipelineStageMetrics {

    private static final Logger log = LoggerFactory.getLogger(PipelineStageMetrics.class);
    static final Set<String> STAGES = Set.of("download", "audio", "frames", "gemini");

    private final MeterRegistry registry;

    public PipelineStageMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 첫 작업 전 0 표본이 수집되도록 고정된 조합만 미리 등록한다. 히스토그램은 켜지 않는다.
        for (Outcome outcome : Outcome.values()) {
            executionTimer(outcome);
            analysisStageTimer("preparing", outcome);
            for (ProcessingStage stage : ProcessingStage.values()) {
                analysisStageTimer(stage.name().toLowerCase(Locale.ROOT), outcome);
            }
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE, NO_PLACES, DISCARDED;

        private String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private Timer executionTimer(Outcome outcome) {
        return Timer.builder("trova.analysis.execution")
                .description("분석 선점 후 결과 저장까지의 실행 시간. 큐 대기·자동 초안 제외")
                .tag("outcome", outcome.tag()).register(registry);
    }

    private Timer analysisStageTimer(String stage, Outcome outcome) {
        return Timer.builder("trova.analysis.stage")
                .description("Java 분석 단계 전체 경과 시간. 단계 상태 DB 갱신 포함")
                .tags("stage", stage, "outcome", outcome.tag()).register(registry);
    }

    public AnalysisTiming startAnalysis(Long jobId) {
        return new AnalysisTiming(jobId);
    }

    /** 작업별 객체. jobId는 로그에만 남기고 지표 라벨로 사용하지 않는다. */
    public final class AnalysisTiming implements AutoCloseable {
        private final Long jobId;
        private final long startedAt = registry.config().clock().monotonicTime();
        private long stageStartedAt = startedAt;
        private String stage = "preparing";
        private boolean finished;

        private AnalysisTiming(Long jobId) {
            this.jobId = jobId;
        }

        public void stage(ProcessingStage next) {
            if (finished) {
                return;
            }
            long now = registry.config().clock().monotonicTime();
            recordStage(Outcome.SUCCESS, now);
            stage = next.name().toLowerCase(Locale.ROOT);
            stageStartedAt = now;
        }

        public void finish(Outcome outcome) {
            if (finished) {
                return;
            }
            finished = true;
            long now = registry.config().clock().monotonicTime();
            recordStage(outcome, now);
            try {
                executionTimer(outcome).record(now - startedAt, TimeUnit.NANOSECONDS);
                log.info("ProcessingJob {} 분석 실행 시간: {}ms ({})", jobId,
                        TimeUnit.NANOSECONDS.toMillis(now - startedAt), outcome.tag());
            } catch (RuntimeException e) {
                log.warn("ProcessingJob {} 분석 실행 지표 기록 실패", jobId, e);
            }
        }

        private void recordStage(Outcome outcome, long now) {
            try {
                analysisStageTimer(stage, outcome).record(now - stageStartedAt, TimeUnit.NANOSECONDS);
                log.info("ProcessingJob {} Java 단계 시간: {} {}ms ({})", jobId, stage,
                        TimeUnit.NANOSECONDS.toMillis(now - stageStartedAt), outcome.tag());
            } catch (RuntimeException e) {
                // 계측 실패가 원래 분석 결과나 예외를 바꾸지 않게 한다.
                log.warn("ProcessingJob {} Java 단계 지표 기록 실패: {}", jobId, stage, e);
            }
        }

        @Override
        public void close() {
            finish(Outcome.FAILURE);
        }
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
