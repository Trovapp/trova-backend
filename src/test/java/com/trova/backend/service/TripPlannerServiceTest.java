package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.planner.OpeningHoursService;
import com.trova.backend.planner.PlanRequestParser;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripDraftRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripPlannerServiceTest {

    private final TripDraftRepository drafts = mock(TripDraftRepository.class);
    private final ProcessingJobRepository jobs = mock(ProcessingJobRepository.class);
    private final SavedPlaceRepository saved = mock(SavedPlaceRepository.class);
    private final PlanRequestParser parser = mock(PlanRequestParser.class);
    private final OpeningHoursService hours = mock(OpeningHoursService.class);
    private final TripPlannerService service = new TripPlannerService(drafts, jobs, saved, parser, hours);
    private final User user = userWithId(1L);

    // 저장 전 엔티티는 id가 없어 소유자 비교를 할 수 없다 — id만 돌려주는 가짜 사용자를 쓴다.
    private static User userWithId(long id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        return u;
    }

    private ProcessingJob job(long id) {
        ProcessingJob job = new ProcessingJob(user, "https://www.youtube.com/shorts/" + id, SourcePlatform.YOUTUBE);
        when(jobs.findById(id)).thenReturn(Optional.of(job));
        return job;
    }

    private SavedPlace place(ProcessingJob job, Double lat, Double lng) {
        return new SavedPlace(job, user, "p", null, "attraction", lat, lng);
    }

    private TripDraft draft(List<Long> jobIds) {
        TripDraft draft = new TripDraft(user, jobIds, "이 영상들로 1박 2일");
        when(drafts.findById(9L)).thenReturn(Optional.of(draft));
        when(parser.parse(any())).thenReturn(new PlanRequestParser.PlanRequest(2, null, "CODE"));
        return draft;
    }

    @Test
    void 영상_지역이_멀면_영업시간을_받지_않고_질문하고_멈춘다() {
        TripDraft draft = draft(List.of(1L, 2L));
        ProcessingJob busan = job(1L);
        ProcessingJob seoul = job(2L);
        when(saved.findByProcessingJob(busan)).thenReturn(List.of(place(busan, 35.16, 129.16)));
        when(saved.findByProcessingJob(seoul)).thenReturn(List.of(place(seoul, 37.57, 126.98)));

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.NEEDS_INPUT);
        assertThat(draft.getQuestion()).contains("km");
        assertThat(draft.getDays()).isEqualTo(2);
        verify(hours, never()).fillMissing(anyList());
    }

    @Test
    void 가까운_영상들이면_영업시간을_채우고_준비_완료로_요약을_남긴다() {
        TripDraft draft = draft(List.of(1L));
        ProcessingJob gimhae = job(1L);
        when(saved.findByProcessingJob(gimhae)).thenReturn(List.of(place(gimhae, 35.23, 128.88), place(gimhae, null, null)));
        when(hours.fillMissing(anyList())).thenReturn(1);

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.READY);
        assertThat(draft.getSummaryJson()).contains("\"totalPlaces\":2").contains("\"placesWithCoords\":1")
                .contains("\"hoursCallsThisRun\":1");
    }

    @Test
    void 장소가_하나도_없으면_실패로_남긴다() {
        TripDraft draft = draft(List.of(1L));
        ProcessingJob empty = job(1L);
        when(saved.findByProcessingJob(empty)).thenReturn(List.of());

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.FAILED);
    }

    @Test
    void 남의_영상이나_끝나지_않은_분석으로는_만들지_않는다() {
        User other = userWithId(2L);
        ProcessingJob othersJob = new ProcessingJob(other, "https://www.youtube.com/shorts/z", SourcePlatform.YOUTUBE);
        when(jobs.findById(5L)).thenReturn(Optional.of(othersJob));

        assertThat(service.create(user, List.of(5L), "1박 2일")).isEmpty();
        assertThat(service.create(user, List.of(), "1박 2일")).isEmpty();
        assertThat(service.create(user, List.of(1L, 2L, 3L, 4L, 5L, 6L), "1박 2일")).isEmpty();
        verify(drafts, never()).save(any());
    }
}
