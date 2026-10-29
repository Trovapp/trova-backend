package com.trova.backend.config;

import com.google.genai.types.HttpOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiConfigTest {

    @Test
    void Gemini_SDK는_다시_보내지_않고_20초에서_끊는다() {
        HttpOptions options = SpringAiConfig.httpOptions();

        assertThat(options.timeout()).contains(20_000);
        assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
    }
}
