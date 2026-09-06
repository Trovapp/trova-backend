package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

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
        if (summary.highlights() == null || summary.highlights().isBlank()) {
            throw new PipelineException("리뷰 요약 출력에 highlights 필드가 없습니다: " + stdout);
        }
        // pros/cons/tips/checklist 키 자체가 응답에서 빠지면 Jackson이 null을 넣는다 —
        // Python 쪽 자체 검증(_validate_summary)이 1차로 막지만, 방어적으로 한 번 더
        // 빈 리스트로 채워서 이후 로직(캐싱/렌더링)이 null을 신경 쓰지 않게 한다.
        return new ReviewSummary(
                summary.highlights(),
                nullToEmpty(summary.pros()),
                nullToEmpty(summary.cons()),
                summary.hours(),
                summary.fee(),
                nullToEmpty(summary.tips()),
                nullToEmpty(summary.checklist())
        );
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list != null ? list : List.of();
    }
}
