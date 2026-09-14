package com.trova.backend.pipeline;

import java.util.List;

public record ExtractedPlace(
        String name, String region, String category, Double confidence,
        Integer dayNumber, Integer orderInDay, List<String> nameCandidates
) {
    public ExtractedPlace {
        if (nameCandidates == null || nameCandidates.isEmpty()) {
            nameCandidates = List.of(name);
        }
    }
}
