package com.trova.backend.service;

import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.replan.TripReplanProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripReplanJobServiceTest {

    @Mock private TripReplanJobLifecycleService lifecycleService;
    @Mock private TripReplanGraph tripReplanGraph;

    @InjectMocks
    private TripReplanJobService tripReplanJobService;

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private TripReplanJobLifecycleService.JobContext newContext(Long userId, Long tripId, boolean indoorOnly) throws Exception {
        User user = new User("google", "u" + userId, "테스트유저", null);
        setId(user, userId);
        Trip trip = new Trip(user, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1));
        setId(trip, tripId);
        return new TripReplanJobLifecycleService.JobContext(user, trip, indoorOnly);
    }

    @Test
    void 성공하면_결과가_JSON으로_직렬화되어_markDone에_전달된다() throws Exception {
        TripReplanJobLifecycleService.JobContext context = newContext(1L, 10L, true);
        when(lifecycleService.markProcessing(5L)).thenReturn(context);

        AlternativeCandidate candidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        TripReplanGraph.ReplanMatch match = new TripReplanGraph.ReplanMatch(1L, "야외공원", candidate);
        TripReplanGraph.ReplanOutcome outcome = new TripReplanGraph.ReplanOutcome(List.of(match), List.of());
        when(tripReplanGraph.run(eq(context.user()), eq(context.trip()), eq(true), any()))
                .thenReturn(outcome);

        tripReplanJobService.process(5L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(lifecycleService).markDone(eq(5L), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).contains("\"tripPlaceId\":1").contains("\"placeId\":99");
    }

    @Test
    void 실패하면_예외메시지로_markFailed가_호출된다() {
        when(lifecycleService.markProcessing(6L))
                .thenThrow(new IllegalStateException("TripReplanJob을 찾을 수 없습니다: 6"));

        tripReplanJobService.process(6L);

        verify(lifecycleService).markFailed(6L, "TripReplanJob을 찾을 수 없습니다: 6");
    }

    @Test
    void 진행률_콜백이_updateProgress로_전달된다() throws Exception {
        TripReplanJobLifecycleService.JobContext context = newContext(2L, 11L, true);
        when(lifecycleService.markProcessing(7L)).thenReturn(context);

        ArgumentCaptor<TripReplanProgressListener> listenerCaptor =
                ArgumentCaptor.forClass(TripReplanProgressListener.class);
        when(tripReplanGraph.run(eq(context.user()), eq(context.trip()), eq(true), listenerCaptor.capture()))
                .thenReturn(new TripReplanGraph.ReplanOutcome(List.of(), List.of()));

        tripReplanJobService.process(7L);
        listenerCaptor.getValue().onProgress(2, 5);

        verify(lifecycleService).updateProgress(7L, 2, 5);
    }
}
