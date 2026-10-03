package com.trova.backend.planner;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesHoursResponse;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpeningHoursServiceTest {

    private final GooglePlacesApiClient google = mock(GooglePlacesApiClient.class);
    private final SavedPlaceRepository repo = mock(SavedPlaceRepository.class);
    private final OpeningHoursService service = new OpeningHoursService(google, repo, mock(ApiCallLogService.class));

    private static SavedPlace place(String name, Double lat, Double lng) {
        User user = new User("google", "g", "u", null);
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/x", SourcePlatform.YOUTUBE);
        return new SavedPlace(job, user, name, null, "restaurant", lat, lng);
    }

    private static GooglePlacesHoursResponse.Place gp(String id, double lat, double lng, List<GooglePlacesHoursResponse.Period> periods) {
        return new GooglePlacesHoursResponse.Place(id, new GooglePlacesHoursResponse.Location(lat, lng),
                periods == null ? null : new GooglePlacesHoursResponse.OpeningHours(periods));
    }

    private static GooglePlacesHoursResponse.Period openOn(int day) {
        return new GooglePlacesHoursResponse.Period(new GooglePlacesHoursResponse.Point(day, 10, 0),
                new GooglePlacesHoursResponse.Point(day, 20, 0));
    }

    @Test
    void 좌표_300m_안에서_가장_가까운_결과의_영업시간을_저장한다() {
        SavedPlace udon = place("요미우돈교자", 35.1630, 129.1600);
        // 2km 떨어진 같은 이름 지점과 50m 떨어진 진짜 가게
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble())).thenReturn(
                new GooglePlacesHoursResponse(List.of(gp("far", 35.18, 129.16, List.of(openOn(1))),
                        gp("near", 35.1634, 129.1600, List.of(openOn(2), openOn(3))))));

        int calls = service.fillMissing(List.of(udon));

        assertThat(calls).isEqualTo(1);
        assertThat(udon.getGooglePlaceId()).isEqualTo("near");
        assertThat(udon.getHoursCheckedAt()).isNotNull();
        // 2026-10-05는 월요일(Google day 1) — near는 화·수만 연다
        assertThat(OpeningHoursService.isOpenOn(udon.getOpeningPeriods(), LocalDate.of(2026, 10, 5))).contains(false);
        assertThat(OpeningHoursService.isOpenOn(udon.getOpeningPeriods(), LocalDate.of(2026, 10, 6))).contains(true);
        verify(repo).save(udon);
    }

    @Test
    void 근처에_맞는_결과가_없어도_확인했다고_남겨_다시_묻지_않는다() {
        SavedPlace p = place("어딘가", 35.0, 129.0);
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesHoursResponse(List.of(gp("far", 36.0, 129.0, List.of(openOn(1))))));

        service.fillMissing(List.of(p));
        int second = service.fillMissing(List.of(p));

        assertThat(p.getHoursCheckedAt()).isNotNull();
        assertThat(p.getOpeningPeriods()).isNull();
        assertThat(second).isZero();
    }

    @Test
    void 조회가_실패하면_확인_표시를_남기지_않아_다음에_다시_시도한다() {
        SavedPlace p = place("어딘가", 35.0, 129.0);
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("timeout"));

        service.fillMissing(List.of(p));

        assertThat(p.getHoursCheckedAt()).isNull();
        verify(repo, never()).save(p);
    }

    @Test
    void 좌표가_없는_장소는_묻지_않는다() {
        assertThat(service.fillMissing(List.of(place("해외", null, null)))).isZero();
    }

    @Test
    void 영업시간이_없거나_24시간이면_그에_맞게_판정한다() {
        assertThat(OpeningHoursService.isOpenOn(null, LocalDate.of(2026, 10, 5))).isEmpty();
        String allDay = "[{\"open\":{\"day\":0,\"hour\":0,\"minute\":0}}]";
        assertThat(OpeningHoursService.isOpenOn(allDay, LocalDate.of(2026, 10, 5))).contains(true);
    }
}
