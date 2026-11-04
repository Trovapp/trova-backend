package com.trova.backend.pipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 스크립트(run_pipeline.py)의 결과. timings는 단계별 밀리초(download·audio·frames·gemini, #194) —
 * 예전 스크립트나 유튜브 링크 직접 분석처럼 없을 수 있어 빈 값으로 둔다.
 * resources는 Python 작업 프로세스의 CPU·메모리·요청 준비 시간(#311, worker_resources.py)이고 processWallMs는 Java가 잰
 * 프로세스 시작~종료 시간이다. 예전 스크립트면 비어 있다.
 */
public record PipelineOutput(String title, List<ExtractedPlace> places, Map<String, Long> timings, Map<String, Long> resources) {

    public PipelineOutput {
        timings = timings == null ? Map.of() : timings;
        resources = resources == null ? Map.of() : resources;
    }

    public PipelineOutput(String title, List<ExtractedPlace> places) {
        this(title, places, Map.of(), Map.of());
    }

    public PipelineOutput withProcessWallMs(long millis) {
        Map<String, Long> merged = new HashMap<>(resources);
        merged.put("processWallMs", millis);
        return new PipelineOutput(title, places, timings, Map.copyOf(merged));
    }
}
