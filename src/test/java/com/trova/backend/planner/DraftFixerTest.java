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
}
