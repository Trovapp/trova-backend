package com.trova.backend.pipeline;

import java.util.List;
import java.util.Map;

/**
 * 분석 스크립트(run_pipeline.py)의 결과. timings는 단계별 밀리초(download·audio·frames·gemini, #194) —
 * 예전 스크립트나 유튜브 링크 직접 분석처럼 없을 수 있어 빈 값으로 둔다.
 */
public record PipelineOutput(String title, List<ExtractedPlace> places, Map<String, Long> timings) {

    public PipelineOutput {
        timings = timings == null ? Map.of() : timings;
    }

    public PipelineOutput(String title, List<ExtractedPlace> places) {
        this(title, places, Map.of());
    }
}
