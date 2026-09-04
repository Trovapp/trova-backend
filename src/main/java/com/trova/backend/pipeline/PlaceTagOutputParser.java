package com.trova.backend.pipeline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class PlaceTagOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PlaceTagOutputParser() {
    }

    public static List<PlaceTag> parse(String stdout) {
        List<PlaceTag> tags;
        try {
            tags = MAPPER.readValue(stdout, MAPPER.getTypeFactory().constructCollectionType(List.class, PlaceTag.class));
        } catch (Exception e) {
            throw new PipelineException("장소 태깅 출력 파싱 실패: " + e.getMessage(), e);
        }

        for (PlaceTag tag : tags) {
            if (tag.index() == null || tag.mood() == null || tag.space() == null) {
                throw new PipelineException("장소 태깅 출력에 필수 필드가 없습니다: " + tag);
            }
        }
        return tags;
    }
}
