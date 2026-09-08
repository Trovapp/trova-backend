package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceVerificationOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                [
                  {"index": 0, "valid": false},
                  {"index": 1, "valid": true}
                ]
                """;

        List<PlaceVerification> verdicts = PlaceVerificationOutputParser.parse(stdout);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).index()).isEqualTo(0);
        assertThat(verdicts.get(0).valid()).isFalse();
        assertThat(verdicts.get(1).index()).isEqualTo(1);
        assertThat(verdicts.get(1).valid()).isTrue();
    }

    @Test
    void valid가_누락되면_예외를_던진다() {
        String stdout = """
                [{"index": 0}]
                """;

        assertThrows(PipelineException.class, () -> PlaceVerificationOutputParser.parse(stdout));
    }

    @Test
    void index가_누락되면_예외를_던진다() {
        String stdout = """
                [{"valid": true}]
                """;

        assertThrows(PipelineException.class, () -> PlaceVerificationOutputParser.parse(stdout));
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        assertThrows(PipelineException.class, () -> PlaceVerificationOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void 빈_배열도_정상_파싱된다() {
        List<PlaceVerification> verdicts = PlaceVerificationOutputParser.parse("[]");

        assertThat(verdicts).isEmpty();
    }
}
