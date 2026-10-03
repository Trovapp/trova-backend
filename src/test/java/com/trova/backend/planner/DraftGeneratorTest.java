package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftGeneratorTest {

    private final GeminiJsonClient gemini = mock(GeminiJsonClient.class);
    private final DraftGenerator generator = new DraftGenerator(gemini);

    private static SavedPlace place(long id, String name, String category, String periods) {
        SavedPlace p = mock(SavedPlace.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getPlaceName()).thenReturn(name);
        lenient().when(p.getCategory()).thenReturn(category);
        lenient().when(p.getLatitude()).thenReturn(35.2);
        lenient().when(p.getLongitude()).thenReturn(128.9);
        lenient().when(p.getOpeningPeriods()).thenReturn(periods);
        return p;
    }

    private final List<SavedPlace> places = List.of(
            place(1, "수로왕릉", "attraction", null),
            place(2, "밀양돼지국밥", "restaurant", null),
            place(3, "연지공원", "attraction", null));

    private static final String VALID = """
            {"days":[{"day":1,"items":[{"placeId":2,"start":"12:00","end":"13:00"},{"placeId":1,"start":"10:00","end":"11:30"}]},
                     {"day":2,"items":[]}],
             "excluded":[{"placeId":3,"reason":"동선에서 멀어요"}]}
            """;

    @Test
    void 형식이_맞으면_한_번에_초안을_만들고_시각순으로_정렬한다() {
        when(gemini.generateJson(anyString(), eq(DraftGenerator.OPERATION))).thenReturn(Optional.of(VALID));

        DraftGenerator.Result r = generator.generate(2, null, places);

        assertThat(r.geminiCalls()).isEqualTo(1);
        DraftGenerator.Draft d = r.draft().orElseThrow();
        assertThat(d.days().get(0).items()).extracting(DraftGenerator.Item::name).containsExactly("수로왕릉", "밀양돼지국밥");
        assertThat(d.days().get(0).items().get(0).start()).isEqualTo(LocalTime.of(10, 0));
        assertThat(d.excluded()).extracting(DraftGenerator.Excluded::reason).containsExactly("동선에서 멀어요");
        assertThat(d.lodging()).singleElement().asString().contains("1일차").contains("밀양돼지국밥");
        assertThat(d.assumptions()).anyMatch(a -> a.contains("날짜를 몰라"));
        verify(gemini, never()).generateJson(anyString(), eq(DraftGenerator.REPAIR_OPERATION));
    }

    @Test
    void 장소가_빠지면_이유를_알려_한_번_더_받는다() {
        String missing = """
                {"days":[{"day":1,"items":[{"placeId":1,"start":"10:00","end":"11:00"}]}],"excluded":[]}
                """;
        when(gemini.generateJson(anyString(), eq(DraftGenerator.OPERATION))).thenReturn(Optional.of(missing));
        when(gemini.generateJson(contains("빠진 placeId [2, 3]"), eq(DraftGenerator.REPAIR_OPERATION)))
                .thenReturn(Optional.of(VALID));

        DraftGenerator.Result r = generator.generate(2, null, places);

        assertThat(r.geminiCalls()).isEqualTo(2);
        assertThat(r.draft()).isPresent();
    }

    @Test
    void 다시_받아도_틀리면_초안을_만들지_않는다() {
        String unknown = """
                {"days":[{"day":1,"items":[{"placeId":99,"start":"10:00","end":"11:00"}]}],"excluded":[]}
                """;
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.of(unknown));

        DraftGenerator.Result r = generator.generate(2, null, places);

        assertThat(r.draft()).isEmpty();
        assertThat(r.geminiCalls()).isEqualTo(2);
        assertThat(r.failure()).contains("목록에 없는 placeId 99");
    }

    @Test
    void 요청_일수_밖의_날이나_거꾸로_된_시각은_형식_오류다() {
        assertThat(DraftGenerator.parse("""
                {"days":[{"day":3,"items":[]}],"excluded":[]}""", 2, null, places).error()).contains("day는 1~2");
        assertThat(DraftGenerator.parse("""
                {"days":[{"day":1,"items":[{"placeId":1,"start":"12:00","end":"11:00"},{"placeId":2,"start":"13:00","end":"14:00"},{"placeId":3,"start":"15:00","end":"16:00"}]}],"excluded":[]}""",
                2, null, places).error()).contains("시각이 잘못됨");
    }

    @Test
    void 날짜가_있으면_휴무일을_프롬프트에_알려준다() {
        // 월요일(Google day 1)만 쉬는 국밥집, 2026-10-05는 월요일
        String closedMonday = "[" + java.util.stream.IntStream.of(0, 2, 3, 4, 5, 6)
                .mapToObj(d -> "{\"open\":{\"day\":" + d + ",\"hour\":10,\"minute\":0},\"close\":{\"day\":" + d + ",\"hour\":20,\"minute\":0}}")
                .reduce((a, b) -> a + "," + b).orElse("") + "]";
        List<SavedPlace> withHours = List.of(place(2, "밀양돼지국밥", "restaurant", closedMonday));

        String prompt = DraftGenerator.prompt(2, LocalDate.of(2026, 10, 4), withHours);

        assertThat(prompt).contains("1일차=2026-10-04(일)").contains("휴무: 2일차");
    }

    @Test
    void 저장용_JSON은_날짜와_시각을_문자열로_쓴다() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.of(VALID));
        DraftGenerator.Draft d = generator.generate(2, LocalDate.of(2026, 10, 10), places).draft().orElseThrow();

        String json = DraftGenerator.toJson(d);

        assertThat(json).contains("\"date\":\"2026-10-10\"").contains("\"start\":\"10:00\"");
    }
}
