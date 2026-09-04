package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceTagOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                [
                  {"index": 0, "mood": "TRENDY", "space": "INDOOR"},
                  {"index": 1, "mood": "CALM", "space": "OUTDOOR"}
                ]
                """;

        List<PlaceTag> tags = PlaceTagOutputParser.parse(stdout);

        assertThat(tags).hasSize(2);
        assertThat(tags.get(0).mood()).isEqualTo("TRENDY");
        assertThat(tags.get(0).space()).isEqualTo("INDOOR");
        assertThat(tags.get(1).index()).isEqualTo(1);
    }

    @Test
    void index가_누락되면_예외를_던진다() {
        String stdout = "[{\"mood\": \"CALM\", \"space\": \"INDOOR\"}]";

        assertThrows(PipelineException.class, () -> PlaceTagOutputParser.parse(stdout));
    }

    @Test
    void mood가_누락되면_예외를_던진다() {
        String stdout = "[{\"index\": 0, \"space\": \"INDOOR\"}]";

        assertThrows(PipelineException.class, () -> PlaceTagOutputParser.parse(stdout));
    }

    @Test
    void space가_누락되면_예외를_던진다() {
        String stdout = "[{\"index\": 0, \"mood\": \"CALM\"}]";

        assertThrows(PipelineException.class, () -> PlaceTagOutputParser.parse(stdout));
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        assertThrows(PipelineException.class, () -> PlaceTagOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void 빈_배열도_정상_파싱된다() {
        List<PlaceTag> tags = PlaceTagOutputParser.parse("[]");

        assertThat(tags).isEmpty();
    }
}
