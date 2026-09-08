package com.trova.backend.pipeline;

import java.util.List;

public record PipelineOutput(String title, List<ExtractedPlace> places) {
}
