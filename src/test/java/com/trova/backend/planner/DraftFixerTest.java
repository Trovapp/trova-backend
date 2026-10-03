package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.trova.backend.planner.PlanFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class DraftFixerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final SavedPlace museum = place(1, "박물관", "attraction", 35.100, 129.030, CLOSED_MONDAY);
    private final SavedPlace beach = place(2, "해수욕장", "attraction", 35.101, 129.031, EVERY_DAY);
    private final SavedPlace market = place(3, "시장", "shopping", 35.102, 129.032, EVERY_DAY);
    private final SavedPlace tower = place(4, "전망대", "attraction", 35.103, 129.033, EVERY_DAY);

    @Test
    void 휴무일에_잡힌_장소를_여는_날로_옮기고_시각을_다시_매긴다() {
        DraftGenerator.Draft d = draft(List.of(
                List.of(item(museum, "10:00", "11:30"), item(beach, "12:00", "13:00")),
                List.of(item(market, "10:00", "11:00"), item(tower, "11:30", "12:30"))));

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(museum, beach, market, tower), MONDAY);

        assertThat(fixed.draft().days().get(0).items()).extracting(DraftGenerator.Item::name).containsExactly("해수욕장");
        List<DraftGenerator.Item> day2 = fixed.draft().days().get(1).items();
        assertThat(day2).extracting(DraftGenerator.Item::name).contains("박물관").hasSize(3);
        // 머무는 시간(90분)은 지키고, 앞뒤 장소와 겹치지 않는다.
        DraftGenerator.Item moved = day2.stream().filter(i -> i.name().equals("박물관")).findFirst().orElseThrow();
        assertThat(java.time.Duration.between(moved.start(), moved.end()).toMinutes()).isEqualTo(90);
        for (int i = 0; i + 1 < day2.size(); i++) {
            assertThat(day2.get(i + 1).start()).isAfterOrEqualTo(day2.get(i).end());
        }
        assertThat(fixed.fixes()).anyMatch(f -> f.contains("박물관은 1일차 휴무라 2일차로 옮겼어요"));
        assertThat(DraftValidator.validate(fixed.draft(), byId(museum, beach, market, tower), 2, MONDAY).violations())
                .noneMatch(v -> v.type().equals("CLOSED"));
    }

    @Test
    void 여는_날이_없으면_이유와_함께_뺀다() {
        DraftGenerator.Draft d = draft(List.of(List.of(item(museum, "10:00", "11:30"), item(beach, "12:00", "13:00"))));

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(museum, beach), MONDAY);

        assertThat(fixed.draft().excluded()).extracting(DraftGenerator.Excluded::name).containsExactly("박물관");
        assertThat(fixed.draft().excluded().get(0).reason()).contains("문을 여는 날이 없어");
    }

    @Test
    void 식당_자리는_두고_나머지를_가까운_곳_순으로_바꾼다() {
        SavedPlace a = place(10, "A", "attraction", 35.00, 129.00, null);
        SavedPlace far = place(11, "먼곳", "attraction", 35.20, 129.00, null);   // A에서 약 22km
        SavedPlace near = place(12, "가까운곳", "attraction", 35.01, 129.00, null); // A에서 약 1km
        SavedPlace food = place(13, "식당", "restaurant", 35.21, 129.00, null);
        List<DraftGenerator.Item> day = List.of(item(a, "10:00", "11:00"), item(far, "11:30", "12:00"),
                item(near, "12:10", "12:50"), item(food, "13:00", "14:00"));

        List<DraftGenerator.Item> out = DraftFixer.reorder(day);

        assertThat(out).extracting(DraftGenerator.Item::name).containsExactly("A", "가까운곳", "먼곳", "식당");
        // 시각 칸은 원래 자리 것을 쓴다.
        assertThat(out.get(1).start()).isEqualTo(LocalTime.of(11, 30));
        assertThat(out.get(3).start()).isEqualTo(LocalTime.of(13, 0));
        assertThat(DraftFixer.pathKm(out)).isLessThan(DraftFixer.pathKm(day));
    }

    @Test
    void 거의_같은_동선이면_AI가_정한_순서를_그대로_둔다() {
        List<DraftGenerator.Item> day = List.of(item(museum, "10:00", "11:00"), item(market, "11:30", "12:00"),
                item(beach, "12:10", "12:50"));

        assertThat(DraftFixer.reorder(day)).isSameAs(day);
    }

    // ---- 근거 없이 뺀 장소 되살리기(#108) ----

    private static DraftGenerator.Draft withExcluded(List<List<DraftGenerator.Item>> days, SavedPlace... excluded) {
        DraftGenerator.Draft d = draft(days);
        List<DraftGenerator.Excluded> ex = new java.util.ArrayList<>();
        for (SavedPlace p : excluded) {
            ex.add(new DraftGenerator.Excluded(p.getId(), p.getPlaceName(), "동선과 시간상 여유가 부족해 뺐어요."));
        }
        return new DraftGenerator.Draft(d.days(), ex, List.of(), List.of());
    }

    @Test
    void 자리가_남는데_뺀_장소는_가까운_자리에_되살리고_앞_시각은_그대로_둔다() {
        // 운영 김해 사례: 4곳뿐인 날에 만리향 만두를 "시간 부족"으로 뺐다.
        SavedPlace royal = place(20, "수로왕릉", "attraction", 35.2353, 128.8785, null);
        SavedPlace gukbap = place(21, "밀양돼지국밥", "restaurant", 35.2368, 128.9042, null);
        SavedPlace park = place(22, "연지공원", "attraction", 35.2408, 128.8789, null);
        SavedPlace gaya = place(23, "가야랜드", "attraction", 35.2605, 128.9020, null);
        SavedPlace mandu = place(24, "만리향 만두", "restaurant", 35.2330, 128.8810, null);
        DraftGenerator.Draft d = withExcluded(List.of(List.of(item(royal, "09:30", "11:00"), item(gukbap, "11:30", "12:30"),
                item(park, "13:00", "14:30"), item(gaya, "15:00", "16:30"))), mandu);

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(royal, gukbap, park, gaya, mandu), null);

        List<DraftGenerator.Item> day = fixed.draft().days().get(0).items();
        assertThat(day).extracting(DraftGenerator.Item::name).contains("만리향 만두").hasSize(5);
        assertThat(fixed.draft().excluded()).isEmpty();
        assertThat(day.get(0).start()).isEqualTo(LocalTime.of(9, 30));
        for (int i = 0; i + 1 < day.size(); i++) {
            assertThat(day.get(i + 1).start()).isAfterOrEqualTo(day.get(i).end());
        }
        assertThat(day.get(day.size() - 1).end()).isBeforeOrEqualTo(DraftFixer.DAY_END);
        assertThat(fixed.fixes()).anyMatch(f -> f.contains("만리향 만두은 1일차에 자리가 있어 다시 넣었어요"));
    }

    @Test
    void 사실로_확인되는_이유로_뺀_장소는_그대로_둔다() {
        SavedPlace a = place(30, "해운대해수욕장", "attraction", 35.1587, 129.1604, null);
        SavedPlace b = place(31, "동백섬", "attraction", 35.1530, 129.1520, null);
        SavedPlace dup = place(32, "해운대 바다", "attraction", 35.1590, 129.1610, null);        // 약 70m — 중복
        SavedPlace far = place(33, "통도사", "attraction", 35.4880, 129.0630, null);           // 약 38km
        SavedPlace station = place(34, "부산역", "other", 35.1150, 129.0410, null);            // 지나가는 곳
        SavedPlace noCoord = org.mockito.Mockito.mock(SavedPlace.class);
        org.mockito.Mockito.lenient().when(noCoord.getId()).thenReturn(35L);
        org.mockito.Mockito.lenient().when(noCoord.getPlaceName()).thenReturn("좌표없는곳");
        DraftGenerator.Draft d = withExcluded(List.of(List.of(item(a, "10:00", "11:30"), item(b, "12:00", "13:00"))),
                dup, far, station, noCoord);

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(a, b, dup, far, station, noCoord), null);

        assertThat(fixed.draft().excluded()).extracting(DraftGenerator.Excluded::name)
                .containsExactlyInAnyOrder("해운대 바다", "통도사", "부산역", "좌표없는곳");
        assertThat(fixed.draft().days().get(0).items()).hasSize(2);
    }

    @Test
    void 그날_휴무거나_하루_7곳이_찼거나_21시를_넘기면_되살리지_않는다() {
        // 월요일(10/5) 하루: 휴무인 박물관은 못 넣고, 7곳 찬 날에도 못 넣는다.
        SavedPlace open = place(40, "해수욕장", "attraction", 35.101, 129.031, EVERY_DAY);
        SavedPlace closedMon = place(41, "월요휴무관", "attraction", 35.102, 129.032, CLOSED_MONDAY);
        DraftGenerator.Draft closed = withExcluded(List.of(List.of(item(open, "10:00", "11:00"))), closedMon);
        assertThat(DraftFixer.fix(closed, byId(open, closedMon), LocalDate.of(2026, 10, 5)).draft().excluded()).hasSize(1);

        List<DraftGenerator.Item> full = new java.util.ArrayList<>();
        SavedPlace[] seven = new SavedPlace[7];
        for (int i = 0; i < 7; i++) {
            seven[i] = place(50 + i, "곳" + i, "cafe", 35.10 + i * 0.001, 129.03, null);
            full.add(item(seven[i], String.format("%02d:00", 9 + i), String.format("%02d:30", 9 + i)));
        }
        SavedPlace extra = place(60, "더", "cafe", 35.11, 129.03, null);
        java.util.Map<Long, SavedPlace> all = byId(seven);
        all.put(60L, extra);
        assertThat(DraftFixer.fix(withExcluded(List.of(full), extra), all, null).draft().excluded()).hasSize(1);

        SavedPlace late1 = place(70, "늦은1", "attraction", 35.10, 129.03, null);
        SavedPlace late2 = place(71, "늦은2", "attraction", 35.101, 129.031, null);
        SavedPlace more = place(72, "하나더", "attraction", 35.102, 129.032, null);
        DraftGenerator.Draft lateDay = withExcluded(List.of(List.of(item(late1, "17:00", "18:30"), item(late2, "19:00", "20:30"))), more);
        assertThat(DraftFixer.fix(lateDay, byId(late1, late2, more), null).draft().excluded()).hasSize(1);
    }

    @Test
    void 사이에_넣으면_겹치는_만큼만_뒤로_민다() {
        SavedPlace a = place(80, "A", "attraction", 35.00, 129.00, null);
        SavedPlace b = place(81, "B", "restaurant", 35.01, 129.00, null);
        SavedPlace x = place(82, "X", "cafe", 35.005, 129.00, null);
        List<DraftGenerator.Item> day = List.of(item(a, "10:00", "11:00"), item(b, "18:00", "19:00"));

        List<DraftGenerator.Item> out = DraftFixer.insertAndShift(day, 1,
                new DraftGenerator.Item(82L, "X", "cafe", LocalTime.of(10, 0), LocalTime.of(10, 50), 35.005, 129.00));

        assertThat(out).extracting(DraftGenerator.Item::name).containsExactly("A", "X", "B");
        assertThat(out.get(1).start()).isEqualTo(LocalTime.of(11, 10)); // 11:00 + 이동 10분
        assertThat(out.get(2).start()).isEqualTo(LocalTime.of(18, 0));  // 저녁 칸은 그대로
    }

    // ---- 식사 칸 맞추기(#110) ----

    private final SavedPlace s1 = place(200, "관광1", "attraction", 33.45, 126.50, null);
    private final SavedPlace s2 = place(201, "관광2", "attraction", 33.46, 126.51, null);
    private final SavedPlace s3 = place(202, "관광3", "attraction", 33.47, 126.52, null);

    @Test
    void 점심_칸이_비면_10시반_이후_식당을_점심으로_옮기고_아침_식당은_둔다() {
        // #108 재측정 B4 1일차 모양: 식당이 09:00·10:40·17:40에 몰려 점심이 비었다.
        SavedPlace haejang = place(210, "해장국", "restaurant", 33.45, 126.50, null);
        SavedPlace noodle = place(211, "국수", "restaurant", 33.451, 126.501, null);
        SavedPlace dinner = place(212, "저녁집", "restaurant", 33.47, 126.52, null);
        List<DraftGenerator.Item> day = List.of(item(haejang, "09:00", "09:50"), item(noodle, "10:40", "11:30"),
                item(s1, "11:40", "13:10"), item(s2, "13:30", "15:00"), item(s3, "15:30", "17:00"), item(dinner, "17:40", "18:40"));
        List<String> fixes = new java.util.ArrayList<>();

        List<DraftGenerator.Item> out = DraftFixer.fitMeals(day, byId(haejang, noodle, dinner, s1, s2, s3), null, 1, fixes);

        DraftGenerator.Item moved = out.stream().filter(i -> i.name().equals("국수")).findFirst().orElseThrow();
        assertThat(moved.start()).isAfterOrEqualTo(LocalTime.of(11, 0)).isBefore(LocalTime.of(14, 0));
        assertThat(out.get(0).name()).isEqualTo("해장국");
        assertThat(out.get(0).start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(out).hasSize(6);
        for (int i = 0; i + 1 < out.size(); i++) {
            assertThat(out.get(i + 1).start()).isAfterOrEqualTo(out.get(i).end());
        }
        assertThat(fixes).anyMatch(f -> f.contains("국수을 1일차 점심 시간"));
        DraftGenerator.Draft fixed = draft(List.of(out));
        assertThat(DraftValidator.validate(fixed, byId(haejang, noodle, dinner, s1, s2, s3), 1, null).violations())
                .noneMatch(v -> v.type().equals("NO_LUNCH") || v.type().equals("NO_DINNER"));
    }

    @Test
    void 남는_식당은_저녁_칸으로_옮기고_점심은_지킨다() {
        SavedPlace lunch = place(220, "점심집", "restaurant", 33.45, 126.50, null);
        SavedPlace extra = place(221, "또식당", "restaurant", 33.46, 126.51, null);
        List<DraftGenerator.Item> day = List.of(item(s1, "10:00", "11:30"), item(lunch, "12:00", "13:00"),
                item(extra, "14:00", "15:00"), item(s2, "15:30", "17:00"), item(s3, "17:30", "19:00"));

        List<DraftGenerator.Item> out = DraftFixer.fitMeals(day, byId(lunch, extra, s1, s2, s3), null, 2, new java.util.ArrayList<>());

        DraftGenerator.Item moved = out.stream().filter(i -> i.name().equals("또식당")).findFirst().orElseThrow();
        assertThat(moved.start()).isAfterOrEqualTo(LocalTime.of(17, 0)).isBefore(LocalTime.of(20, 0));
        assertThat(out.stream().filter(i -> i.name().equals("점심집")).findFirst().orElseThrow().start()).isEqualTo(LocalTime.of(12, 0));
        assertThat(out.get(out.size() - 1).end()).isBeforeOrEqualTo(DraftFixer.DAY_END);
    }

    @Test
    void 옮길_식당이_없거나_21시를_넘기면_그대로_둔다() {
        SavedPlace onlyLunch = place(230, "점심만", "restaurant", 33.45, 126.50, null);
        List<DraftGenerator.Item> day = List.of(item(s1, "10:00", "11:30"), item(onlyLunch, "12:00", "13:00"),
                item(s2, "14:00", "16:00"), item(s3, "17:00", "19:00"));
        assertThat(DraftFixer.fitMeals(day, byId(onlyLunch, s1, s2, s3), null, 1, new java.util.ArrayList<>())).isSameAs(day);

        // 저녁 칸으로 옮기면 뒤 일정이 21시를 넘는다.
        SavedPlace lunch = place(231, "점심집", "restaurant", 33.45, 126.50, null);
        SavedPlace late = place(232, "늦은식당", "restaurant", 33.46, 126.51, null);
        List<DraftGenerator.Item> tight = List.of(item(lunch, "12:00", "13:00"), item(late, "14:00", "15:00"),
                item(s1, "17:00", "19:30"), item(s2, "19:40", "20:50"));
        List<DraftGenerator.Item> out = DraftFixer.fitMeals(tight, byId(lunch, late, s1, s2), null, 1, new java.util.ArrayList<>());
        assertThat(out.get(out.size() - 1).end()).isBeforeOrEqualTo(DraftFixer.DAY_END);
    }

    // ---- 되살리기 시간 조건(#112) ----

    @Test
    void 점심_저녁이_다_차_있으면_식당은_되살리지_않는다() {
        // 운영 제주 1일차 모양: 저녁 17:30이 이미 있는데 식당을 19:20에 또 넣었다.
        SavedPlace lunch = place(300, "점심집", "restaurant", 33.50, 126.53, null);
        SavedPlace dinner = place(301, "저녁집", "restaurant", 33.41, 126.27, null);
        SavedPlace extra = place(302, "또식당", "restaurant", 33.24, 126.31, null);
        DraftGenerator.Draft d = withExcluded(List.of(List.of(item(s1, "10:00", "11:30"), item(lunch, "12:00", "13:00"),
                item(s2, "14:00", "15:30"), item(dinner, "17:30", "18:30"))), extra);

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(lunch, dinner, extra, s1, s2), null);

        assertThat(fixed.draft().excluded()).extracting(DraftGenerator.Excluded::name).containsExactly("또식당");
    }

    @Test
    void 저녁_칸이_비어_있으면_식당을_저녁_시간에_되살린다() {
        SavedPlace lunch = place(310, "점심집", "restaurant", 33.45, 126.50, null);
        SavedPlace extra = place(311, "저녁될집", "restaurant", 33.465, 126.515, null);
        DraftGenerator.Draft d = withExcluded(List.of(List.of(item(s1, "10:00", "11:30"), item(lunch, "12:00", "13:00"),
                item(s2, "14:00", "15:30"))), extra);

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(lunch, extra, s1, s2), null);

        DraftGenerator.Item restored = fixed.draft().days().get(0).items().stream()
                .filter(i -> i.name().equals("저녁될집")).findFirst().orElseThrow();
        assertThat(restored.start()).isAfterOrEqualTo(LocalTime.of(17, 0)).isBefore(LocalTime.of(20, 0));
    }

    @Test
    void 관광지는_18시_넘어_끝나는_자리에는_되살리지_않는다() {
        // 운영 제주 2일차 모양: 저녁 뒤 송악산 19:10.
        SavedPlace dinner = place(320, "저녁집", "restaurant", 33.46, 126.51, null);
        SavedPlace mountain = place(321, "오름", "attraction", 33.47, 126.52, null);
        DraftGenerator.Draft d = withExcluded(List.of(List.of(item(s1, "09:00", "11:00"), item(s2, "11:10", "13:30"),
                item(s3, "13:40", "17:20"), item(dinner, "17:30", "18:30"))), mountain);

        DraftFixer.Fixed fixed = DraftFixer.fix(d, byId(dinner, mountain, s1, s2, s3), null);

        assertThat(fixed.draft().excluded()).extracting(DraftGenerator.Excluded::name).containsExactly("오름");
    }
}
