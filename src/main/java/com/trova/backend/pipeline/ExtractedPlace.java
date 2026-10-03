package com.trova.backend.pipeline;

import java.util.List;

public record ExtractedPlace(
        String name, String region, String category, Double confidence,
        Integer dayNumber, Integer orderInDay, List<String> nameCandidates,
        // 게시물 설명·화면에 명시된 주소(#61). 없으면 null — 좌표를 찾을 때 이름보다 먼저 쓴다.
        String address,
        // 영상이 이 장소에 대해 말하거나 보여준 구체 정보(추천 메뉴·가격·웨이팅 등, #104). 없으면 빈 목록.
        List<String> videoNotes
) {
    public ExtractedPlace {
        if (nameCandidates == null || nameCandidates.isEmpty()) {
            nameCandidates = List.of(name);
        }
        videoNotes = videoNotes == null ? List.of() : List.copyOf(videoNotes);
    }

    public ExtractedPlace(
            String name, String region, String category, Double confidence,
            Integer dayNumber, Integer orderInDay, List<String> nameCandidates, String address
    ) {
        this(name, region, category, confidence, dayNumber, orderInDay, nameCandidates, address, List.of());
    }

    public ExtractedPlace(
            String name, String region, String category, Double confidence,
            Integer dayNumber, Integer orderInDay, List<String> nameCandidates
    ) {
        this(name, region, category, confidence, dayNumber, orderInDay, nameCandidates, null, List.of());
    }
}
