package com.trova.backend.pipeline;

import com.trova.backend.entity.ProcessingStage;
import io.micrometer.core.instrument.DistributionSummary;
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

    /**
     * Python 작업 프로세스의 CPU·메모리(#311)를 지표와 로그 한 줄로 남긴다. Rust 등 다른 구현과 비교할 기준값이다.
     * 지표 태그는 고정 값만 쓴다. 값이 없거나 음수인 항목은 건너뛴다(예전 스크립트는 resources가 비어 있다).
     *
     * @return 로그에 붙일 한 줄, 남긴 게 없으면 null
     */
    public String recordResources(Long jobId, Map<String, Long> resources) {
        if (resources.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Long selfCpu = sum(resources.get("cpuUserMs"), resources.get("cpuSystemMs"));
        Long childCpu = sum(resources.get("childCpuUserMs"), resources.get("childCpuSystemMs"));
        if (selfCpu != null) {
            cpuSummary("worker").record(selfCpu);
            parts.add("Python CPU " + selfCpu + "ms");
        }
        if (childCpu != null) {
            cpuSummary("children").record(childCpu);
            parts.add("자식 CPU " + childCpu + "ms");
        }
        Long rss = valid(resources.get("maxRssKib"));
        if (rss != null) {
            rssSummary("worker").record(rss * 1024.0);
            parts.add("Python 최대 메모리 " + String.format(Locale.ROOT, "%.1f", rss / 1024.0) + "MiB");
        }
        Long childRss = valid(resources.get("childMaxRssKib"));
        if (childRss != null && childRss > 0) {
            rssSummary("largest_child").record(childRss * 1024.0);
            parts.add("가장 큰 자식 " + String.format(Locale.ROOT, "%.1f", childRss / 1024.0) + "MiB");
        }
        Long processWall = valid(resources.get("processWallMs"));
        Long workerWall = valid(resources.get("workerWallMs"));
        if (processWall != null && workerWall != null && processWall >= workerWall) {
            long overhead = processWall - workerWall;
            Timer.builder("trova.pipeline.worker.overhead")
                    .description("Python 프로세스 전체 시간 - 스크립트가 잰 시간(인터프리터 시작·모듈 읽기·종료)")
                    .register(registry).record(Duration.ofMillis(overhead));
            parts.add("시작·종료 " + overhead + "ms");
        }
        Long buildParts = valid(resources.get("buildPartsUs"));
        if (buildParts != null) {
            prepTimer("build_parts").record(buildParts, TimeUnit.MICROSECONDS);
            parts.add("요청 parts " + String.format(Locale.ROOT, "%.1f", buildParts / 1000.0) + "ms");
        }
        Long requestJson = valid(resources.get("requestJsonUs"));
        if (requestJson != null) {
            prepTimer("request_json").record(requestJson, TimeUnit.MICROSECONDS);
            parts.add("요청 JSON " + String.format(Locale.ROOT, "%.1f", requestJson / 1000.0) + "ms");
        }
        Long requestBytes = valid(resources.get("requestBytes"));
        if (requestBytes != null) {
            parts.add("요청 " + String.format(Locale.ROOT, "%.1f", requestBytes / 1024.0) + "KiB");
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private DistributionSummary cpuSummary(String process) {
        return DistributionSummary.builder("trova.pipeline.worker.cpu")
                .description("영상 분석 1건의 CPU 시간(사용자+시스템). Gemini 응답 대기는 들어가지 않는다")
                .baseUnit("milliseconds").tag("process", process).register(registry);
    }

    private DistributionSummary rssSummary(String process) {
        return DistributionSummary.builder("trova.pipeline.worker.max_rss")
                .description("영상 분석 1건의 최대 메모리(RSS). largest_child는 자식 중 가장 큰 하나이며 합계가 아니다")
                .baseUnit("bytes").tag("process", process).register(registry);
    }

    private Timer prepTimer(String part) {
        return Timer.builder("trova.pipeline.request_prep")
                .description("Gemini 요청 준비 시간 합계(build_parts: 파일 읽기·base64, request_json: 요청 본문 만들기)")
                .tag("part", part).register(registry);
    }

    private static Long valid(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private static Long sum(Long a, Long b) {
        Long first = valid(a);
        Long second = valid(b);
        return first == null || second == null ? null : first + second;
    }
}
