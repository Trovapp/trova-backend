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

        DraftGenerator.Result r = generator.generate(2, null, places, List.of());

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

        DraftGenerator.Result r = generator.generate(2, null, places, List.of());

        assertThat(r.geminiCalls()).isEqualTo(2);
        assertThat(r.draft()).isPresent();
    }

    @Test
    void 다시_받아도_틀리면_초안을_만들지_않는다() {
        String unknown = """
                {"days":[{"day":1,"items":[{"placeId":99,"start":"10:00","end":"11:00"}]}],"excluded":[]}
                """;
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.of(unknown));

        DraftGenerator.Result r = generator.generate(2, null, places, List.of());

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

        String prompt = DraftGenerator.prompt(2, LocalDate.of(2026, 10, 4), withHours, List.of("지역별로 날을 나누세요."));
        assertThat(prompt).contains("- 지역별로 날을 나누세요.\n- 모든 장소는");

        assertThat(prompt).contains("1일차=2026-10-04(일)").contains("휴무: 2일차");
    }

    @Test
    void 저장용_JSON은_날짜와_시각을_문자열로_쓴다() {
        when(gemini.generateJson(anyString(), anyString())).thenReturn(Optional.of(VALID));
        DraftGenerator.Draft d = generator.generate(2, LocalDate.of(2026, 10, 10), places, List.of()).draft().orElseThrow();

        String json = DraftGenerator.toJson(d);

        assertThat(json).contains("\"date\":\"2026-10-10\"").contains("\"start\":\"10:00\"");
    }

    @Test
    void 저장한_JSON을_다시_읽으면_같은_초안이다() {
        when(gemini.generateJson(anyString(), eq(DraftGenerator.OPERATION))).thenReturn(Optional.of(VALID));
        DraftGenerator.Draft d = generator.generate(2, LocalDate.of(2026, 10, 5), places, List.of()).draft().orElseThrow();

        assertThat(DraftGenerator.fromJson(DraftGenerator.toJson(d))).isEqualTo(d);
        assertThat(DraftGenerator.toJson(d, List.of("고침"), List.of("남음"))).contains("\"fixes\":[\"고침\"]")
                .contains("\"problems\":[\"남음\"]");
    }

    @Test
    void 규칙_수정은_지금_초안과_문제를_알려_한_번_부른다() {
        when(gemini.generateJson(anyString(), eq(DraftGenerator.OPERATION))).thenReturn(Optional.of(VALID));
        DraftGenerator.Draft d = generator.generate(2, null, places, List.of()).draft().orElseThrow();
        when(gemini.generateJson(contains("[반드시] 수로왕릉 휴무"), eq(DraftGenerator.RULE_REPAIR_OPERATION)))
                .thenReturn(Optional.of(VALID));

        DraftGenerator.Result r = generator.repairRules(2, null, places, List.of(), d, List.of("[반드시] 수로왕릉 휴무"));

        assertThat(r.geminiCalls()).isEqualTo(1);
        assertThat(r.draft()).isPresent();
        verify(gemini).generateJson(contains("\"placeId\":1"), eq(DraftGenerator.RULE_REPAIR_OPERATION));
    }

    @Test
    void 영상_속_숙소가_있으면_그날_마지막_장소에서_가까운_숙소를_안내한다() {
        SavedPlace near = place(7, "가까운 숙소", "lodging", null);
        SavedPlace far = mock(SavedPlace.class);
        lenient().when(far.getPlaceName()).thenReturn("먼 숙소");
        lenient().when(far.getLatitude()).thenReturn(37.5);
        lenient().when(far.getLongitude()).thenReturn(127.0);
        DraftGenerator.Item last = new DraftGenerator.Item(1L, "수로왕릉", "attraction", LocalTime.of(10, 0), LocalTime.of(11, 0), 35.2, 128.9);
        List<DraftGenerator.Day> days = List.of(new DraftGenerator.Day(1, null, List.of(last)), new DraftGenerator.Day(2, null, List.of()));

        assertThat(DraftGenerator.lodging(days, List.of(far, near))).containsExactly("1일차 숙소: 가까운 숙소(영상 속 숙소)");
        assertThat(DraftGenerator.lodging(days, List.of())).singleElement().asString().contains("숙소 미정");
    }
}
