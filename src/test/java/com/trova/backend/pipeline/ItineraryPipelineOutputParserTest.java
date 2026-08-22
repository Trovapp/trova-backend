package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItineraryPipelineOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                [
                  {"id": 1, "dayNumber": 1, "orderInDay": 1},
                  {"id": 2, "dayNumber": 1, "orderInDay": 2}
                ]
                """;

        List<ItineraryAssignment> assignments = ItineraryPipelineOutputParser.parse(stdout);

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).id()).isEqualTo(1L);
        assertThat(assignments.get(0).dayNumber()).isEqualTo(1);
        assertThat(assignments.get(1).orderInDay()).isEqualTo(2);
    }

    @Test
    void dayNumber가_누락되면_예외를_던진다() {
        String stdout = """
                [{"id": 1, "orderInDay": 1}]
                """;

        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse(stdout));
    }

    @Test
    void 일부_항목만_필드가_누락돼도_전체가_실패한다() {
        String stdout = """
                [
                  {"id": 1, "dayNumber": 1, "orderInDay": 1},
                  {"id": 2, "dayNumber": 1}
                ]
                """;

        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse(stdout));
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        assertThrows(PipelineException.class, () -> ItineraryPipelineOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void 빈_배열도_정상_파싱된다() {
        List<ItineraryAssignment> assignments = ItineraryPipelineOutputParser.parse("[]");

        assertThat(assignments).isEmpty();
    }
}
