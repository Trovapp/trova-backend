package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class PipelineOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PipelineOutputParser() {
    }

    public static PipelineOutput parse(String stdout) {
        try {
            return MAPPER.readValue(stdout, PipelineOutput.class);
        } catch (Exception e) {
            throw new PipelineException("파이프라인 출력 파싱 실패: " + e.getMessage(), e);
        }
    }
}
