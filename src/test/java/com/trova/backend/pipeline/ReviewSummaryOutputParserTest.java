package com.trova.backend.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSummaryOutputParserTest {

    @Test
    void 정상_JSON을_ReviewSummary로_파싱한다() {
        String stdout = """
                {
                  "highlights": "탁 트인 바다와 기암괴석이 만드는 절경으로 유명해요.",
                  "pros": ["경치가 좋다", "산책로가 잘 되어있다"],
                  "cons": ["휴게공간이 낡았다"],
                  "hours": "하절기 8시, 동절기 18시 30분까지",
                  "fee": "다누비열차 성인 4천원, 학생 2천원",
                  "tips": ["6시 이후엔 자차만 진입 가능"],
                  "checklist": ["계절별 운영시간 확인"]
                }
                """;

        ReviewSummary result = ReviewSummaryOutputParser.parse(stdout);

        assertThat(result.highlights()).isEqualTo("탁 트인 바다와 기암괴석이 만드는 절경으로 유명해요.");
        assertThat(result.pros()).containsExactly("경치가 좋다", "산책로가 잘 되어있다");
        assertThat(result.cons()).containsExactly("휴게공간이 낡았다");
        assertThat(result.hours()).isEqualTo("하절기 8시, 동절기 18시 30분까지");
        assertThat(result.fee()).isEqualTo("다누비열차 성인 4천원, 학생 2천원");
        assertThat(result.tips()).containsExactly("6시 이후엔 자차만 진입 가능");
        assertThat(result.checklist()).containsExactly("계절별 운영시간 확인");
    }

    @Test
    void hours와_fee는_null이어도_통과한다() {
        String stdout = """
                {"highlights": "요약", "pros": [], "cons": [], "hours": null, "fee": null, "tips": [], "checklist": []}
                """;

        ReviewSummary result = ReviewSummaryOutputParser.parse(stdout);

        assertThat(result.hours()).isNull();
        assertThat(result.fee()).isNull();
    }

    @Test
    void pros_cons_tips_checklist_키가_아예_없으면_빈_리스트로_채운다() {
        String stdout = "{\"highlights\": \"요약\"}";

        ReviewSummary result = ReviewSummaryOutputParser.parse(stdout);

        assertThat(result.pros()).isEmpty();
        assertThat(result.cons()).isEmpty();
        assertThat(result.tips()).isEmpty();
        assertThat(result.checklist()).isEmpty();
    }

    @Test
    void highlights_필드가_없으면_예외를_던진다() {
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
