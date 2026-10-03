package com.trova.backend.service;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.planner.DraftGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripDraftApprovalServiceTest {

    private final Map<Long, SavedPlace> places = new HashMap<>();

    private DraftGenerator.Item item(long id, String region) {
        SavedPlace p = mock(SavedPlace.class);
        when(p.getRegion()).thenReturn(region);
        places.put(id, p);
        return new DraftGenerator.Item(id, "곳" + id, "attraction", LocalTime.of(10, 0), LocalTime.of(11, 0), null, null);
    }

    private static DraftGenerator.Draft plan(List<List<DraftGenerator.Item>> days) {
        List<DraftGenerator.Day> list = new java.util.ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            list.add(new DraftGenerator.Day(i + 1, null, days.get(i)));
        }
        return new DraftGenerator.Draft(list, List.of(), List.of(), List.of());
    }

    @Test
    void 날마다_가장_많은_지역을_일차_순으로_붙인다() {
        DraftGenerator.Draft d = plan(List.of(List.of(item(1, "김해"), item(2, "김해 봉황동"), item(3, "부산")),
                List.of(item(4, "강릉 초당동"), item(5, "강릉"))));

        assertThat(TripDraftApprovalService.defaultTitle(d, places)).isEqualTo("김해·강릉 1박 2일");
    }

    @Test
    void 지역이_셋_이상이면_줄이고_모르면_새_여행() {
        DraftGenerator.Draft three = plan(List.of(List.of(item(1, "제주")), List.of(item(2, "부산")), List.of(item(3, "강릉"))));
        DraftGenerator.Draft unknown = plan(List.of(List.of(item(4, null))));

        assertThat(TripDraftApprovalService.defaultTitle(three, places)).isEqualTo("제주·부산 외 2박 3일");
        assertThat(TripDraftApprovalService.defaultTitle(unknown, places)).isEqualTo("새 여행 당일치기");
    }
}
