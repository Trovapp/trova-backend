package com.trova.backend.service;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.TripDraft;
import com.trova.backend.entity.TripDraftStatus;
import com.trova.backend.entity.User;
import com.trova.backend.planner.DraftGenerator;
import com.trova.backend.planner.OpeningHoursService;
import com.trova.backend.planner.PlanRequestParser;
import com.trova.backend.planner.TripPlanGraph;
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
    private final TripPlanGraph graph = mock(TripPlanGraph.class);
    private final TripPlannerService service = new TripPlannerService(drafts, jobs, saved, parser, hours, graph);
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

    private TripDraft autoDraft(List<Long> jobIds) {
        TripDraft draft = TripDraft.auto(user, jobIds, "이 영상들로 1박 2일");
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
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(new DraftGenerator.Day(1, null, List.of())),
                List.of(), List.of(), List.of("이동 시간은 직선거리로 어림했어요."));
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.of(plan), 1, 0, 0, 0, List.of(), List.of(), null));

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.READY);
        assertThat(draft.getDraftJson()).contains("\"day\":1");
        assertThat(draft.getGeminiCalls()).isEqualTo(1); // 요청은 코드로 읽어 0번 + 초안 1번
        assertThat(draft.getSummaryJson()).contains("\"totalPlaces\":2").contains("\"placesWithCoords\":1")
                .contains("\"hoursCallsThisRun\":1");
    }

    @Test
    void 자동_초안은_유료_영업시간_조회를_하지_않는다() {
        TripDraft draft = autoDraft(List.of(1L));
        ProcessingJob gimhae = job(1L);
        when(saved.findByProcessingJob(gimhae)).thenReturn(List.of(place(gimhae, 35.23, 128.88)));
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(new DraftGenerator.Day(1, null, List.of())),
                List.of(), List.of(), List.of());
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.of(plan), 1, 0, 0, 0, List.of(), List.of(), null));

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.READY);
        verify(hours, never()).fillMissing(anyList());
    }

    @Test
    void 사용자가_직접_만든_초안은_영업시간을_조회한다() {
        TripDraft draft = draft(List.of(1L));
        ProcessingJob gimhae = job(1L);
        when(saved.findByProcessingJob(gimhae)).thenReturn(List.of(place(gimhae, 35.23, 128.88)));
        when(hours.fillMissing(anyList())).thenReturn(1);
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(new DraftGenerator.Day(1, null, List.of())),
                List.of(), List.of(), List.of());
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.of(plan), 1, 0, 0, 0, List.of(), List.of(), null));

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.READY);
        verify(hours).fillMissing(anyList());
    }

    @Test
    void 초안을_만들지_못하면_내부_사유_대신_안내_문구로_실패한다() {
        TripDraft draft = draft(List.of(1L));
        ProcessingJob gimhae = job(1L);
        when(saved.findByProcessingJob(gimhae)).thenReturn(List.of(place(gimhae, 35.23, 128.88)));
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.empty(), 2, 0, -1, -1, List.of(), List.of(),
                        "형식 오류: 목록에 없는 placeId 99"));

        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.FAILED);
        assertThat(draft.getErrorMessage()).doesNotContain("placeId");
        assertThat(draft.getGeminiCalls()).isEqualTo(2);
    }

    @Test
    void 영상_속_숙소는_일정_칸이_아니라_숙소_안내로_쓰고_영업시간도_묻지_않는다() {
        TripDraft draft = draft(List.of(1L));
        ProcessingJob jeju = job(1L);
        SavedPlace sight = new SavedPlace(jeju, user, "성산일출봉", null, "attraction", 33.458, 126.942);
        SavedPlace stay = new SavedPlace(jeju, user, "봄빛코티지", null, "lodging", 33.45, 126.9);
        when(saved.findByProcessingJob(jeju)).thenReturn(List.of(sight, stay));
        DraftGenerator.Item item = new DraftGenerator.Item(1L, "성산일출봉", "attraction",
                java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), 33.458, 126.942);
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(new DraftGenerator.Day(1, null, List.of(item)),
                new DraftGenerator.Day(2, null, List.of())), List.of(), List.of(), List.of());
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.of(plan), 1, 0, 0, 0, List.of(), List.of("2일차 0곳"), null));

        service.process(9L);

        verify(hours).fillMissing(List.of(sight));
        verify(graph).run(2, null, List.of(sight), List.of());
        assertThat(draft.getDraftJson()).contains("1일차 숙소: 봄빛코티지(영상 속 숙소)").contains("\"problems\":[\"2일차 0곳\"]");
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

    private TripDraft waitingDraft(List<Long> jobIds) {
        TripDraft draft = new TripDraft(user, jobIds, "1박 2일");
        draft.applyRequest(2, null, "CODE");
        draft.markNeedsInput("영상 속 지역이 서로 300km 넘게 떨어져 있어요.", "{}");
        when(drafts.findByIdForUpdate(9L)).thenReturn(Optional.of(draft));
        when(drafts.findById(9L)).thenReturn(Optional.of(draft));
        return draft;
    }

    @Test
    void 지역별로_나누겠다고_답하면_질문_없이_모든_영상으로_다시_만들고_요청은_다시_읽지_않는다() {
        TripDraft draft = waitingDraft(List.of(1L, 2L));
        ProcessingJob busan = job(1L);
        ProcessingJob seoul = job(2L);
        SavedPlace a = place(busan, 35.16, 129.16);
        SavedPlace b = place(seoul, 37.57, 126.98);
        when(saved.findByProcessingJob(busan)).thenReturn(List.of(a));
        when(saved.findByProcessingJob(seoul)).thenReturn(List.of(b));
        DraftGenerator.Draft plan = new DraftGenerator.Draft(List.of(new DraftGenerator.Day(1, null, List.of()),
                new DraftGenerator.Day(2, null, List.of())), List.of(), List.of(), List.of());
        when(graph.run(org.mockito.ArgumentMatchers.anyInt(), any(), anyList(), anyList()))
                .thenReturn(new TripPlanGraph.Outcome(Optional.of(plan), 1, 0, 0, 0, List.of(), List.of(), null));

        assertThat(service.answer(user, 9L, "SPLIT", null)).isEqualTo(TripPlannerService.AnswerOutcome.OK);
        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.PENDING);
        service.process(9L);

        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.READY);
        verify(parser, never()).parse(any());
        verify(graph).run(2, null, List.of(a, b), List.of(TripPlannerService.SPLIT_NOTE));
    }

    @Test
    void 한_지역만_고르면_그_영상만_남긴다() {
        TripDraft draft = waitingDraft(List.of(1L, 2L));

        assertThat(service.answer(user, 9L, "ONLY", List.of(2L))).isEqualTo(TripPlannerService.AnswerOutcome.OK);

        assertThat(draft.getJobIds()).containsExactly(2L);
        assertThat(draft.getAnswer()).isEqualTo("ONLY");
    }

    @Test
    void 처음_고른_영상이_아니거나_모르는_답이면_거절하고_답을_기다리는_중이_아니면_충돌() {
        TripDraft draft = waitingDraft(List.of(1L, 2L));

        assertThat(service.answer(user, 9L, "ONLY", List.of(3L))).isEqualTo(TripPlannerService.AnswerOutcome.BAD_REQUEST);
        assertThat(service.answer(user, 9L, "ONLY", List.of())).isEqualTo(TripPlannerService.AnswerOutcome.BAD_REQUEST);
        assertThat(service.answer(user, 9L, "MAYBE", null)).isEqualTo(TripPlannerService.AnswerOutcome.BAD_REQUEST);
        assertThat(service.answer(userWithId(2L), 9L, "SPLIT", null)).isEqualTo(TripPlannerService.AnswerOutcome.NOT_FOUND);
        assertThat(draft.getStatus()).isEqualTo(TripDraftStatus.NEEDS_INPUT);

        service.answer(user, 9L, "SPLIT", null);
        assertThat(service.answer(user, 9L, "SPLIT", null)).isEqualTo(TripPlannerService.AnswerOutcome.NOT_WAITING);
    }
}
