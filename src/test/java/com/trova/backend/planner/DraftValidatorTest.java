package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.trova.backend.planner.PlanFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class DraftValidatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private final SavedPlace museum = place(1, "박물관", "attraction", 35.10, 129.03, CLOSED_MONDAY);
    private final SavedPlace beach = place(2, "해수욕장", "attraction", 35.11, 129.04, null);
    private final SavedPlace far = place(3, "먼 사찰", "attraction", 35.60, 129.40, null);

    private static List<String> types(DraftValidator.Report r) {
        return r.violations().stream().map(DraftValidator.Violation::type).toList();
    }

    @Test
    void 휴무일에_잡힌_장소는_ERROR이고_영업시간을_모르는_장소는_판정_불가로_센다() {
        DraftGenerator.Draft d = draft(List.of(List.of(item(museum, "10:00", "11:00"), item(beach, "11:30", "12:30"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(museum, beach), 1, MONDAY);

        assertThat(types(r)).containsExactly("CLOSED");
        assertThat(r.errors()).isEqualTo(1);
        assertThat(r.unknownHours()).isEqualTo(1);
    }

    @Test
    void 날짜를_모르면_휴무를_판정하지_않는다() {
        DraftGenerator.Draft d = draft(List.of(List.of(item(museum, "10:00", "11:00"), item(beach, "11:30", "12:30"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(museum, beach), 1, null);

        assertThat(r.errors()).isZero();
        assertThat(r.unknownHours()).isEqualTo(2);
    }

    @Test
    void 문_열기_전_방문은_ERROR() {
        DraftGenerator.Draft d = draft(List.of(List.of(item(museum, "08:00", "09:00"), item(beach, "09:30", "10:30"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(museum, beach), 1, MONDAY.plusDays(1));

        assertThat(types(r)).containsExactly("OUTSIDE_HOURS");
    }

    @Test
    void 자정을_넘겨_닫는_곳은_그날_밤까지_연_것으로_본다() {
        String overnight = "[{\"open\":{\"day\":2,\"hour\":18,\"minute\":0},\"close\":{\"day\":3,\"hour\":2,\"minute\":0}}]";
        assertThat(DraftValidator.withinHours(overnight, MONDAY.plusDays(1), LocalTime.of(21, 0), LocalTime.of(22, 0))).isTrue();
        assertThat(DraftValidator.withinHours(overnight, MONDAY.plusDays(1), LocalTime.of(17, 0), LocalTime.of(18, 30))).isFalse();
    }

    @Test
    void 연속_이동이_30km를_넘으면_ERROR() {
        DraftGenerator.Draft d = draft(List.of(List.of(item(beach, "10:00", "11:00"), item(far, "12:00", "13:00"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(beach, far), 1, null);

        assertThat(types(r)).containsExactly("HOP_TOO_FAR");
        assertThat(r.violations().get(0).message()).contains("해수욕장→먼 사찰");
    }

    @Test
    void 하루_8곳은_ERROR_하루_1곳은_WARNING이고_일수가_다르면_ERROR() {
        List<DraftGenerator.Item> many = new java.util.ArrayList<>();
        SavedPlace[] all = new SavedPlace[9];
        for (int i = 0; i < 9; i++) {
            all[i] = place(10 + i, "곳" + i, "attraction", 35.10, 129.03, null);
            if (i < 8) {
                many.add(item(all[i], String.format("%02d:00", 9 + i), String.format("%02d:30", 9 + i)));
            }
        }
        DraftGenerator.Draft d = draft(List.of(many, List.of(item(all[8], "10:00", "11:00"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(all), 3, null);

        assertThat(r.violations()).extracting(DraftValidator.Violation::type, DraftValidator.Violation::severity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("DAYS", DraftValidator.Severity.ERROR),
                        org.assertj.core.groups.Tuple.tuple("DAY_TOO_FULL", DraftValidator.Severity.ERROR),
                        org.assertj.core.groups.Tuple.tuple("DAY_TOO_LIGHT", DraftValidator.Severity.WARNING));
    }

    @Test
    void 식당이_있는데_점심_저녁_시간대에_식당이_없으면_WARNING() {
        SavedPlace food = place(4, "국밥집", "restaurant", 35.10, 129.03, null);
        SavedPlace cafe = place(5, "카페", "cafe", 35.10, 129.03, null);
        DraftGenerator.Draft d = draft(List.of(List.of(item(beach, "10:00", "11:00"), item(food, "15:00", "16:00"),
                item(cafe, "17:30", "18:30"))));

        DraftValidator.Report r = DraftValidator.validate(d, byId(beach, food, cafe), 1, null);

        assertThat(types(r)).containsExactlyInAnyOrder("NO_LUNCH", "NO_DINNER");
        assertThat(r.errors()).isZero();
    }

    @Test
    void 장소가_충분한데_빈_날이_있으면_ERROR이고_장소가_모자라면_WARNING만() {
        SavedPlace[] ps = new SavedPlace[8];
        List<DraftGenerator.Item> d1 = new java.util.ArrayList<>();
        List<DraftGenerator.Item> d2 = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ps[i] = place(100 + i, "곳" + i, "attraction", 35.10, 129.03, null);
            (i < 5 ? d1 : d2).add(item(ps[i], String.format("%02d:00", 9 + i % 5), String.format("%02d:30", 9 + i % 5)));
        }
        DraftValidator.Report r = DraftValidator.validate(draft(List.of(d1, d2, List.of())), byId(ps), 3, null);
        assertThat(r.violations()).extracting(DraftValidator.Violation::type, DraftValidator.Violation::severity)
                .contains(org.assertj.core.groups.Tuple.tuple("EMPTY_DAY", DraftValidator.Severity.ERROR));

        SavedPlace only = place(120, "한곳", "restaurant", 35.10, 129.03, null);
        DraftValidator.Report one = DraftValidator.validate(draft(List.of(List.of(item(only, "12:00", "13:00")), List.of())),
                byId(only), 2, null);
        assertThat(one.errors()).isZero();
    }
}
