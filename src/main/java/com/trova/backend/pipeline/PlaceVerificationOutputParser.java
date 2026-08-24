package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class PlaceVerificationOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PlaceVerificationOutputParser() {
    }

    public static List<PlaceVerification> parse(String stdout) {
        List<PlaceVerification> verdicts;
        try {
            verdicts = MAPPER.readValue(
                    stdout,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, PlaceVerification.class));
        } catch (Exception e) {
            throw new PipelineException("장소 검증 출력 파싱 실패: " + e.getMessage(), e);
        }

        for (PlaceVerification verdict : verdicts) {
            if (verdict.index() == null || verdict.valid() == null) {
                throw new PipelineException("장소 검증 출력에 누락된 필드가 있습니다: " + verdict);
            }
        }
        return verdicts;
    }
}
