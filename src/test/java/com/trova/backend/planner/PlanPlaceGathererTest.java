package com.trova.backend.planner;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPlaceGathererTest {

    private static SavedPlace place(Double lat, Double lng) {
        User user = new User("google", "g", "u", null);
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/x", SourcePlatform.YOUTUBE);
        return new SavedPlace(job, user, "p", null, "attraction", lat, lng);
    }

    @Test
    void 영상_지역이_100km_넘게_떨어지면_시작_전에_묻는다() {
        var busan = new PlanPlaceGatherer.VideoPlaces(1L, "부산", List.of(place(35.16, 129.16), place(35.10, 129.03)));
        var seoul = new PlanPlaceGatherer.VideoPlaces(2L, "서울", List.of(place(37.57, 126.98)));

        PlanPlaceGatherer.Gathered g = PlanPlaceGatherer.gather(List.of(busan, seoul));

        assertThat(g.maxVideoDistanceKm()).isGreaterThan(300);
        assertThat(g.question()).isPresent();
        assertThat(g.totalPlaces()).isEqualTo(3);
    }

    @Test
    void 가까운_영상들은_묻지_않는다() {
        var busan = new PlanPlaceGatherer.VideoPlaces(1L, "부산", List.of(place(35.16, 129.16)));
        var gimhae = new PlanPlaceGatherer.VideoPlaces(2L, "김해", List.of(place(35.23, 128.88), place(35.25, 128.90)));
        assertThat(PlanPlaceGatherer.gather(List.of(busan, gimhae)).question()).isEmpty();
    }

    @Test
    void 좌표_없는_장소만_있는_영상은_거리_계산에서_빠진다() {
        var noCoords = new PlanPlaceGatherer.VideoPlaces(1L, "해외", List.of(place(null, null)));
        assertThat(PlanPlaceGatherer.gather(List.of(noCoords)).maxVideoDistanceKm()).isZero();
    }
}
