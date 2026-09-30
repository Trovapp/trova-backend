package com.trova.backend.pipeline;

import java.util.List;

public record ExtractedPlace(
        String name, String region, String category, Double confidence,
        Integer dayNumber, Integer orderInDay, List<String> nameCandidates,
        // 게시물 설명·화면에 명시된 주소(#61). 없으면 null — 좌표를 찾을 때 이름보다 먼저 쓴다.
        String address
) {
    public ExtractedPlace {
        if (nameCandidates == null || nameCandidates.isEmpty()) {
            nameCandidates = List.of(name);
        }
    }

    public ExtractedPlace(
            String name, String region, String category, Double confidence,
            Integer dayNumber, Integer orderInDay, List<String> nameCandidates
    ) {
        this(name, region, category, confidence, dayNumber, orderInDay, nameCandidates, null);
    }
}
