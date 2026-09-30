package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 하루 한도 표식은 파이썬(extract_places.py)과 자바(PipelineRunner)가 같은 문자열이어야 서버가 알아본다(#63). */
class DailyQuotaMarkerTest {

    @Test
    void 파이프라인_스크립트와_서버의_하루_한도_표식이_같다() throws Exception {
        String script = Files.readString(Path.of("pipeline-test/extract_places.py"));

        assertThat(script).contains("DAILY_QUOTA_MARKER = \"" + PipelineRunner.DAILY_QUOTA_MARKER + "\"");
    }

    @Test
    void 파이프라인_스크립트와_서버의_인스타_속도_제한_표식이_같다() throws Exception {
        String script = Files.readString(Path.of("pipeline-test/run_pipeline.py"));

        assertThat(script).contains("SOURCE_RATE_LIMIT_MARKER = \"" + PipelineRunner.SOURCE_RATE_LIMIT_MARKER + "\"");
    }
}
