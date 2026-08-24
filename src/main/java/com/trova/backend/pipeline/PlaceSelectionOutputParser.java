package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class PlaceSelectionOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PlaceSelectionOutputParser() {
    }

    public static List<PlaceSelection> parse(String stdout) {
        List<PlaceSelection> selections;
        try {
            selections = MAPPER.readValue(
                    stdout,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, PlaceSelection.class));
        } catch (Exception e) {
            throw new PipelineException("장소 선택 출력 파싱 실패: " + e.getMessage(), e);
        }

        for (PlaceSelection selection : selections) {
            if (selection.index() == null) {
                throw new PipelineException("장소 선택 출력에 index가 없습니다: " + selection);
            }
        }
        return selections;
    }
}
