package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class ItineraryPipelineOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ItineraryPipelineOutputParser() {
    }

    public static List<ItineraryAssignment> parse(String stdout) {
        List<ItineraryAssignment> assignments;
        try {
            assignments = MAPPER.readValue(
                    stdout,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ItineraryAssignment.class));
        } catch (Exception e) {
            throw new PipelineException("일정 생성 출력 파싱 실패: " + e.getMessage(), e);
        }

        for (ItineraryAssignment assignment : assignments) {
            if (assignment.id() == null || assignment.dayNumber() == null || assignment.orderInDay() == null) {
                throw new PipelineException("일정 생성 출력에 누락된 필드가 있습니다: " + assignment);
            }
        }
        return assignments;
    }
}
