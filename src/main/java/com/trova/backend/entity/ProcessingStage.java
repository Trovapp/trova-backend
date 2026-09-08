package com.trova.backend.entity;

/**
 * PlaceExtractionService.process()가 실제로 거치는 단계를 그대로 반영한다 — 가짜
 * 퍼센트를 지어내지 않기 위해, 백엔드가 진짜로 도달한 단계만 기록/노출한다.
 * EXTRACTING은 영상 다운로드+STT+AI 추출이 파이썬 서브프로세스 한 번으로 묶여 있어
 * 더 세분화된 진행률을 알 수 없는 구간이다.
 */
public enum ProcessingStage {
    EXTRACTING(20, "AI가 영상을 분석하고 있어요"),
    GEOCODING(50, "장소 위치를 확인하고 있어요"),
    SELECTING(70, "후보 장소를 좁히고 있어요"),
    VERIFYING(90, "정보를 최종 확인하고 있어요"),
    SAVING(100, "저장하고 있어요");

    private final int percent;
    private final String message;

    ProcessingStage(int percent, String message) {
        this.percent = percent;
        this.message = message;
    }

    public int percent() {
        return percent;
    }

    public String message() {
        return message;
    }
}
