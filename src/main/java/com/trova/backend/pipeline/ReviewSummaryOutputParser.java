package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ReviewSummaryOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReviewSummaryOutputParser() {
    }

    public static ReviewSummary parse(String stdout) {
        ReviewSummary summary;
        try {
            summary = MAPPER.readValue(stdout, ReviewSummary.class);
        } catch (Exception e) {
            throw new PipelineException("리뷰 요약 출력 파싱 실패: " + e.getMessage(), e);
        }
        if (summary.summary() == null || summary.summary().isBlank()) {
            throw new PipelineException("리뷰 요약 출력에 summary 필드가 없습니다: " + stdout);
        }
        return summary;
    }
}
