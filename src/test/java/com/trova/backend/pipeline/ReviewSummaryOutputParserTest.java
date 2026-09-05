package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSummaryOutputParserTest {

    @Test
    void 정상_JSON을_ReviewSummary로_파싱한다() {
        String stdout = "{\"summary\": \"전반적으로 만족도가 높은 곳이에요.\"}";

        ReviewSummary result = ReviewSummaryOutputParser.parse(stdout);

        assertThat(result.summary()).isEqualTo("전반적으로 만족도가 높은 곳이에요.");
    }

    @Test
    void summary_필드가_없으면_예외를_던진다() {
        String stdout = "{}";

        assertThatThrownBy(() -> ReviewSummaryOutputParser.parse(stdout))
                .isInstanceOf(PipelineException.class);
    }

    @Test
    void 유효하지_않은_JSON이면_예외를_던진다() {
        assertThatThrownBy(() -> ReviewSummaryOutputParser.parse("이건 JSON이 아님"))
                .isInstanceOf(PipelineException.class);
    }
}
