package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceSelectionOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                [
                  {"index": 0, "selectedCandidateIndex": 1},
                  {"index": 1, "selectedCandidateIndex": 0}
                ]
                """;

        List<PlaceSelection> selections = PlaceSelectionOutputParser.parse(stdout);

        assertThat(selections).hasSize(2);
        assertThat(selections.get(0).index()).isEqualTo(0);
        assertThat(selections.get(0).selectedCandidateIndex()).isEqualTo(1);
        assertThat(selections.get(1).selectedCandidateIndex()).isEqualTo(0);
    }

    @Test
    void selectedCandidateIndex가_null이어도_정상_파싱된다() {
        String stdout = """
                [{"index": 0, "selectedCandidateIndex": null}]
                """;

        List<PlaceSelection> selections = PlaceSelectionOutputParser.parse(stdout);

        assertThat(selections).hasSize(1);
        assertThat(selections.get(0).index()).isEqualTo(0);
        assertThat(selections.get(0).selectedCandidateIndex()).isNull();
    }

    @Test
    void index가_누락되면_예외를_던진다() {
        String stdout = """
                [{"selectedCandidateIndex": 0}]
                """;

        assertThrows(PipelineException.class, () -> PlaceSelectionOutputParser.parse(stdout));
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        assertThrows(PipelineException.class, () -> PlaceSelectionOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void 빈_배열도_정상_파싱된다() {
        List<PlaceSelection> selections = PlaceSelectionOutputParser.parse("[]");

        assertThat(selections).isEmpty();
    }
}
