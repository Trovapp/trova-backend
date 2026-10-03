package com.trova.backend.planner;

import com.trova.backend.entity.SavedPlace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.trova.backend.planner.PlanFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripPlanGraphTest {

    private final DraftGenerator generator = mock(DraftGenerator.class);
    private final TripPlanGraph graph = new TripPlanGraph(generator);

    private final SavedPlace beach = place(1, "해수욕장", "attraction", 35.10, 129.03, null);
    private final SavedPlace market = place(2, "시장", "shopping", 35.11, 129.04, null);
    private final SavedPlace far = place(3, "먼 사찰", "attraction", 35.60, 129.40, null);
    private final List<SavedPlace> places = List.of(beach, market, far);

    // 해수욕장→먼 사찰 약 64km: HOP_TOO_FAR 1건
    private final DraftGenerator.Draft broken = draft(List.of(List.of(item(beach, "10:00", "11:00"), item(far, "12:00", "13:00")),
            List.of(item(market, "10:00", "11:00"))));
    private final DraftGenerator.Draft good = new DraftGenerator.Draft(List.of(
            new DraftGenerator.Day(1, null, List.of(item(beach, "10:00", "11:00"), item(market, "11:30", "12:30"))),
            new DraftGenerator.Day(2, null, List.of())),
            List.of(new DraftGenerator.Excluded(3L, "먼 사찰", "멀어요")), List.of(), List.of());

    private void firstDraft(DraftGenerator.Draft d) {
        when(generator.generate(anyInt(), any(), anyList(), anyList())).thenReturn(new DraftGenerator.Result(Optional.of(d), 1, null));
    }

    @Test
    void 위반이_없으면_AI_수정_없이_끝난다() {
        firstDraft(good);

        TripPlanGraph.Outcome o = graph.run(2, null, places, List.of());

        assertThat(o.draft()).isPresent();
        assertThat(o.geminiCalls()).isEqualTo(1);
        assertThat(o.aiRepairs()).isZero();
        assertThat(o.firstErrors()).isZero();
        verify(generator, never()).repairRules(anyInt(), any(), anyList(), anyList(), any(), anyList());
    }

    @Test
    void 코드로_못_고친_위반은_AI에_맡기고_고쳐지면_멈춘다() {
        firstDraft(broken);
        when(generator.repairRules(anyInt(), any(), anyList(), anyList(), any(), anyList()))
                .thenReturn(new DraftGenerator.Result(Optional.of(good), 1, null));

        TripPlanGraph.Outcome o = graph.run(2, null, places, List.of());

        assertThat(o.aiRepairs()).isEqualTo(1);
        assertThat(o.geminiCalls()).isEqualTo(2);
        assertThat(o.firstErrors()).isEqualTo(1);
        assertThat(o.finalErrors()).isZero();
        assertThat(o.draft().orElseThrow().excluded()).extracting(DraftGenerator.Excluded::name).containsExactly("먼 사찰");
        verify(generator).repairRules(anyInt(), any(), anyList(), anyList(), any(),
                org.mockito.ArgumentMatchers.argThat(problems -> problems.get(0).startsWith("[반드시] 해수욕장→먼 사찰")));
    }

    @Test
    void AI_수정은_최대_2번이고_못_고치면_남은_위반을_알린다() {
        firstDraft(broken);
        when(generator.repairRules(anyInt(), any(), anyList(), anyList(), any(), anyList()))
                .thenReturn(new DraftGenerator.Result(Optional.empty(), 1, "형식 오류"));

        TripPlanGraph.Outcome o = graph.run(2, null, places, List.of());

        verify(generator, times(2)).repairRules(anyInt(), any(), anyList(), anyList(), any(), anyList());
        assertThat(o.geminiCalls()).isEqualTo(3);
        assertThat(o.finalErrors()).isEqualTo(1);
        assertThat(o.draft()).isPresent();
        assertThat(o.problems()).anyMatch(p -> p.contains("해수욕장→먼 사찰"));
        assertThat(o.fixes()).anyMatch(f -> f.contains("쓰지 못했어요"));
    }

    @Test
    void AI가_더_나쁘게_고치면_위반이_가장_적었던_초안을_쓴다() {
        firstDraft(broken);
        DraftGenerator.Draft worse = draft(List.of(List.of(item(beach, "10:00", "11:00"), item(far, "12:00", "13:00"),
                item(market, "14:00", "15:00")), List.of()));
        when(generator.repairRules(anyInt(), any(), anyList(), anyList(), any(), anyList()))
                .thenReturn(new DraftGenerator.Result(Optional.of(worse), 1, null));

        TripPlanGraph.Outcome o = graph.run(2, null, places, List.of());

        assertThat(o.finalErrors()).isEqualTo(1);
        assertThat(o.draft().orElseThrow().days().get(1).items()).extracting(DraftGenerator.Item::name).containsExactly("시장");
    }

    @Test
    void 첫_초안을_못_만들면_실패를_돌려준다() {
        when(generator.generate(anyInt(), any(), anyList(), anyList()))
                .thenReturn(new DraftGenerator.Result(Optional.empty(), 2, "형식 오류: 빠진 placeId"));

        TripPlanGraph.Outcome o = graph.run(2, null, places, List.of());

        assertThat(o.draft()).isEmpty();
        assertThat(o.geminiCalls()).isEqualTo(2);
        assertThat(o.failure()).contains("빠진 placeId");
    }
}
