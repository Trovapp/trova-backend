package com.trova.backend.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI가 만든 문장에서 "AI가 쓴 글"처럼 보이는 기호를 없앤다(#53).
 * 대시·글머리 기호·중간 기호·따옴표만 다루고, 의미가 있는 하이픈(K-POP, 전화번호)과
 * 리뷰 하이라이트의 **강조** 표시(앱이 굵게 보여줌)는 그대로 둔다.
 */
class AiTextSanitizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            해운대 근처 카페예요 — 바다가 보여요             | 해운대 근처 카페예요, 바다가 보여요
            조용해요 – 사람이 적어요                        | 조용해요, 사람이 적어요
            조용해요 - 사람이 적어요                        | 조용해요, 사람이 적어요
            평일 10:00–18:00 운영해요                      | 평일 10:00~18:00 운영해요
            입장료는 3,000—5,000원이에요                   | 입장료는 3,000~5,000원이에요
            "분위기 좋은" 카페예요                          | 분위기 좋은 카페예요
            “뷰 맛집”으로 유명해요                          | 뷰 맛집으로 유명해요
            '인생샷' 명소예요                               | 인생샷 명소예요
            「부산 3대 돼지국밥」 중 하나예요                 | 부산 3대 돼지국밥 중 하나예요
            ※ 주말엔 붐벼요                                | 주말엔 붐벼요
            주차 가능 → 편해요                              | 주차 가능 편해요
            ★ 추천 메뉴는 밀면이에요                        | 추천 메뉴는 밀면이에요
            - 주말엔 붐벼요                                 | 주말엔 붐벼요
            • 예약하면 좋아요                               | 예약하면 좋아요
            1. 오전에 가세요                                | 오전에 가세요
            K-POP 굿즈를 팔아요                             | K-POP 굿즈를 팔아요
            문의는 051-123-4567로 하세요                    | 문의는 051-123-4567로 하세요
            **바다 뷰**가 정말 멋져요                       | **바다 뷰**가 정말 멋져요
            그냥 평범한 문장이에요.                          | 그냥 평범한 문장이에요.
            """)
    void AI_느낌의_기호를_자연스러운_문장으로_바꾼다(String input, String expected) {
        assertThat(AiTextSanitizer.clean(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            첫째 줄이에요\\n- 둘째 줄이에요  | 첫째 줄이에요\\n둘째 줄이에요
            """)
    void 여러_줄이면_줄마다_앞_글머리를_지운다(String input, String expected) {
        assertThat(AiTextSanitizer.clean(input.replace("\\n", "\n"))).isEqualTo(expected.replace("\\n", "\n"));
    }

    @org.junit.jupiter.api.Test
    void null은_null() {
        assertThat(AiTextSanitizer.clean(null)).isNull();
    }
}
