package com.trova.backend.pipeline;

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
}
