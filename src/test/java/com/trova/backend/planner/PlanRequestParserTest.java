package com.trova.backend.planner;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanRequestParserTest {

    // 2026-10-03(토)로 고정
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final GeminiJsonClient gemini = mock(GeminiJsonClient.class);
    private final PlanRequestParser parser = new PlanRequestParser(gemini, clock);

    @Test
    void 정해진_표현은_AI_없이_코드로_읽는다() {
        assertThat(parser.parse("이 영상 3개로 부산 1박 2일 짜 줘")).isEqualTo(new PlanRequestParser.PlanRequest(2, null, "CODE"));
        assertThat(parser.parse("제주 2박3일 코스")).isEqualTo(new PlanRequestParser.PlanRequest(3, null, "CODE"));
        assertThat(parser.parse("전주 당일치기로")).isEqualTo(new PlanRequestParser.PlanRequest(1, null, "CODE"));
        verify(gemini, never()).generateJson(anyString(), anyString());
    }

    @Test
    void 날짜_속_일_표현을_일수로_읽지_않는다() {
        PlanRequestParser.PlanRequest r = parser.parse("11월 1일 여행으로 1박 2일");
        assertThat(r.days()).isEqualTo(2);
        assertThat(r.startDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void 연도_없는_지난_날짜는_내년으로_읽는다() {
        assertThat(parser.parseDate("3월 1일 출발")).contains(LocalDate.of(2027, 3, 1));
        assertThat(parser.parseDate("2026-10-18 출발")).contains(LocalDate.of(2026, 10, 18));
    }

    @Test
    void 일수를_못_읽으면_AI에_한_번_묻는다() {
        when(gemini.generateJson(anyString(), eq("trip-plan.parse-request")))
                .thenReturn(Optional.of("{\"days\": 2, \"startDate\": \"2026-10-10\"}"));

        PlanRequestParser.PlanRequest r = parser.parse("주말에 부산 다녀올래");

        assertThat(r).isEqualTo(new PlanRequestParser.PlanRequest(2, LocalDate.of(2026, 10, 10), "AI"));
    }

    @Test
    void AI도_못_읽으면_당일로_둔다() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(parser.parse("부산 가자")).isEqualTo(new PlanRequestParser.PlanRequest(1, null, "DEFAULT"));
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.of("{\"days\": 30}"));
        assertThat(parser.parse("부산 가자").source()).isEqualTo("DEFAULT"); // 범위 밖(1~7) 값은 버린다
    }
}
