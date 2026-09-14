package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineOutputParserTest {

    @Test
    void 정상_출력을_파싱한다() {
        String stdout = """
                {
                  "title": "부산 여행 브이로그",
                  "places": [
                    {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95},
                    {"name": "송정 씨앗호떡", "region": null, "category": "restaurant", "confidence": 0.8}
                  ]
                }
                """;

        PipelineOutput output = PipelineOutputParser.parse(stdout);

        assertThat(output.title()).isEqualTo("부산 여행 브이로그");
        List<ExtractedPlace> places = output.places();
        assertThat(places).hasSize(2);
        assertThat(places.get(0).name()).isEqualTo("해운대");
        assertThat(places.get(0).region()).isEqualTo("부산");
        assertThat(places.get(0).category()).isEqualTo("attraction");
        assertThat(places.get(0).confidence()).isEqualTo(0.95);
        assertThat(places.get(1).region()).isNull();
    }

    @Test
    void title이_null이어도_places는_정상_파싱된다() {
        String stdout = """
                {
                  "title": null,
                  "places": [
                    {"name": "장소", "region": null, "category": "cafe", "confidence": 0.9}
                  ]
                }
                """;

        PipelineOutput output = PipelineOutputParser.parse(stdout);

        assertThat(output.title()).isNull();
        assertThat(output.places()).hasSize(1);
    }

    @Test
    void 빈_places_배열은_빈_리스트를_반환한다() {
        PipelineOutput output = PipelineOutputParser.parse("""
                {"title": "제목", "places": []}
                """);

        assertThat(output.title()).isEqualTo("제목");
        assertThat(output.places()).isEmpty();
    }

    @Test
    void 예상하지_못한_필드가_있어도_무시하고_파싱한다() {
        String stdout = """
                {
                  "title": "제목",
                  "places": [
                    {"name": "장소", "region": null, "category": "cafe", "confidence": 0.9, "evidence": "화면 자막"}
                  ],
                  "extra": "무시됨"
                }
                """;

        PipelineOutput output = PipelineOutputParser.parse(stdout);

        assertThat(output.places()).hasSize(1);
        assertThat(output.places().get(0).name()).isEqualTo("장소");
    }

    @Test
    void 잘못된_JSON이면_예외를_던진다() {
        org.junit.jupiter.api.Assertions.assertThrows(
                PipelineException.class,
                () -> PipelineOutputParser.parse("이건 JSON이 아님"));
    }

    @Test
    void dayNumber와_orderInDay가_있으면_함께_파싱된다() {
        String stdout = """
                {
                  "title": "일정 영상",
                  "places": [
                    {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95, "dayNumber": 1, "orderInDay": 1},
                    {"name": "서면", "region": "부산", "category": "shopping", "confidence": 0.9, "dayNumber": 2, "orderInDay": 1}
                  ]
                }
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout).places();

        assertThat(places.get(0).dayNumber()).isEqualTo(1);
        assertThat(places.get(0).orderInDay()).isEqualTo(1);
        assertThat(places.get(1).dayNumber()).isEqualTo(2);
    }

    @Test
    void nameCandidates가_있으면_함께_파싱된다() {
        String stdout = """
                {
                  "title": "인천 여행",
                  "places": [
                    {"name": "하늘기", "region": "인천", "category": "attraction", "confidence": 0.6, "nameCandidates": ["하늘기", "하늘길"]}
                  ]
                }
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout).places();

        assertThat(places.get(0).nameCandidates()).containsExactly("하늘기", "하늘길");
    }

    @Test
    void nameCandidates가_없으면_name_하나로_기본값이_채워진다() {
        String stdout = """
                {
                  "title": "인천 여행",
                  "places": [
                    {"name": "청라역", "region": "인천", "category": "attraction", "confidence": 0.95}
                  ]
                }
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout).places();

        assertThat(places.get(0).nameCandidates()).containsExactly("청라역");
    }

    @Test
    void dayNumber와_orderInDay가_없으면_null로_파싱된다() {
        String stdout = """
                {
                  "title": "일반 영상",
                  "places": [
                    {"name": "해운대", "region": "부산", "category": "attraction", "confidence": 0.95}
                  ]
                }
                """;

        List<ExtractedPlace> places = PipelineOutputParser.parse(stdout).places();

        assertThat(places.get(0).dayNumber()).isNull();
        assertThat(places.get(0).orderInDay()).isNull();
    }
}
