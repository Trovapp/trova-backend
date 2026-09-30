package com.trova.backend.pipeline;

import com.trova.backend.service.AiTextSanitizer;

import java.util.List;

public record ReviewSummary(
        String highlights,
        List<String> pros,
        List<String> cons,
        String hours,
        String fee,
        List<String> tips,
        List<String> checklist
) {
    /** AI가 쓴 기호(대시·글머리·따옴표 등)를 뺀 사본(#53). 하이라이트의 **강조**는 앱이 굵게 보여주므로 남는다. */
    public ReviewSummary cleaned() {
        return new ReviewSummary(
                AiTextSanitizer.clean(highlights), cleanAll(pros), cleanAll(cons),
                AiTextSanitizer.clean(hours), AiTextSanitizer.clean(fee), cleanAll(tips), cleanAll(checklist));
    }

    private static List<String> cleanAll(List<String> items) {
        return items == null ? null : items.stream().map(AiTextSanitizer::clean).filter(s -> !s.isBlank()).toList();
    }
}
