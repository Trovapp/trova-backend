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
import java.time.LocalDateTime;
import java.util.Optional;
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

    private static SavedPlace kakaoPlace(String name, String kakaoUrl) {
        User user = new User("google", "g", "u", null);
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/x", SourcePlatform.YOUTUBE);
        return new SavedPlace(job, user, name, "제주", "restaurant", 33.45, 126.5, null, null, null, null, null, null, kakaoUrl);
    }

    @Test
    void 같은_카카오_장소를_최근에_확인했으면_Google을_부르지_않고_복사한다() {
        // #132: 같은 가게를 사용자마다·분석마다 다시 물었다(개발 DB 조회 466회, 확인된 장소 41곳).
        SavedPlace donor = kakaoPlace("우진해장국", "http://place.map.kakao.com/1");
        donor.applyOpeningHours("gp-1", "[{\"open\":{\"day\":1,\"hour\":9,\"minute\":0},\"close\":{\"day\":1,\"hour\":18,\"minute\":0}}]");
        SavedPlace mine = kakaoPlace("우진해장국", "http://place.map.kakao.com/1");
        when(repo.findFirstByKakaoPlaceUrlAndHoursCheckedAtAfterOrderByHoursCheckedAtDesc(
                org.mockito.ArgumentMatchers.eq("http://place.map.kakao.com/1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(donor));

        int calls = service.fillMissing(List.of(mine));

        assertThat(calls).isZero();
        verify(google, never()).searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble());
        assertThat(mine.getGooglePlaceId()).isEqualTo("gp-1");
        assertThat(mine.getOpeningPeriods()).isEqualTo(donor.getOpeningPeriods());
        // 복사한 결과는 원래 확인 시각을 그대로 둔다 — 복사가 이어져도 오래된 영업시간이 새것처럼 남지 않게.
        assertThat(mine.getHoursCheckedAt()).isEqualTo(donor.getHoursCheckedAt());
        verify(repo).save(mine);
    }

    @Test
    void 확인한_지_오래됐거나_카카오_주소가_없으면_Google에_묻는다() {
        SavedPlace noUrl = kakaoPlace("이름만", null);
        when(google.searchTextWithHours(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new GooglePlacesHoursResponse(List.of()));

        int calls = service.fillMissing(List.of(noUrl));

        assertThat(calls).isEqualTo(1);
        verify(repo, never()).findFirstByKakaoPlaceUrlAndHoursCheckedAtAfterOrderByHoursCheckedAtDesc(anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }
}
