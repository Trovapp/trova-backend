package com.trova.backend.conversation;

import com.trova.backend.entity.Place;
import com.trova.backend.entity.SignalType;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.recommendation.GapRecommendationService;
import com.trova.backend.recommendation.PersonalizationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.UserPreferenceSignalRepository;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationToolExecutorTest {

    @Mock private AlternativeFinderService alternativeFinderService;
    @Mock private GapRecommendationService gapRecommendationService;
    @Mock private PersonalizationService personalizationService;
    @Mock private PlaceRepository placeRepository;
    @Mock private UserPreferenceSignalRepository userPreferenceSignalRepository;
    @Mock private PlaceEmbeddingService placeEmbeddingService;
    @Mock private ApiCallLogService apiCallLogService;

    private ConversationToolExecutor executor;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        executor = new ConversationToolExecutor(
                alternativeFinderService, gapRecommendationService, personalizationService, placeRepository,
                userPreferenceSignalRepository, placeEmbeddingService, apiCallLogService);
        user = new User("google", "u1", "테스트유저", null);
        setId(user, 1L);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private AlternativeCandidate candidate(Long placeId, String name) {
        return new AlternativeCandidate(placeId, "g-" + placeId, name, "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
    }

    @Test
    void find_alternatives는_세션의_tripPlaceId로_AlternativeFinderService를_호출한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        List<AlternativeCandidate> candidates = List.of(candidate(5L, "커피한약방"));
        when(alternativeFinderService.findAlternatives(eq(user), eq(100L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(candidates));

        var call = new GeminiChatClient.FunctionCall(
                "find_alternatives", Map.of("category", "카페", "indoor", true), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        ArgumentCaptor<AlternativeFilter> filterCaptor = ArgumentCaptor.forClass(AlternativeFilter.class);
        verify(alternativeFinderService).findAlternatives(eq(user), eq(100L), filterCaptor.capture());
        assertThat(filterCaptor.getValue().category()).isEqualTo("카페");
        assertThat(filterCaptor.getValue().indoorOnly()).isTrue();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.responseForGemini()).containsKey("candidates");
        verify(apiCallLogService).record(
                eq("internal"), eq("conversation-tool-find_alternatives"), eq(null),
                anyLong(), eq(true), eq(null), eq(null), eq(null), eq(null));
    }

    @Test
    void find_alternatives는_후보가_7개_넘으면_상위_7개만_반환한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        // AlternativeFinderService가 이미 개인화 점수 기준 내림차순으로 정렬해서
        // 반환하므로, 여기서는 순서를 그대로 자르기만 하는지 검증한다(재정렬 없음).
        List<AlternativeCandidate> tenSorted = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> candidate((long) i, "장소" + i))
                .toList();
        when(alternativeFinderService.findAlternatives(eq(user), eq(100L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(tenSorted));

        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        assertThat(result.candidates()).hasSize(7);
        assertThat(result.candidates().stream().map(AlternativeCandidate::placeId).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test
    void find_alternatives는_요청_문장과_유사도가_높은_후보를_앞으로_당긴다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        // 3위였던 후보(placeId=3)가 요청 문장과 유사도가 가장 높으면 1위로 올라와야 한다.
        List<AlternativeCandidate> ranked = List.of(
                candidate(1L, "장소1"), candidate(2L, "장소2"), candidate(3L, "장소3"));
        when(alternativeFinderService.findAlternatives(eq(user), eq(100L), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(ranked));
        when(personalizationService.queryScores(eq("조용한 곳 알려줘"), eq(List.of(1L, 2L, 3L))))
                .thenReturn(Map.of(1L, 0.1, 2L, 0.1, 3L, 0.95));

        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "조용한 곳 알려줘");

        assertThat(result.candidates().get(0).placeId()).isEqualTo(3L);
    }

    @Test
    void get_gap_recommendations는_세션의_gapBeforePlaceId와_일치하는_gap만_반환한다() {
        ConversationState state = new ConversationState(1L, 10L, null, 2, 50L);
        var matchingGap = new GapRecommendationService.Gap(50L, 60L, 40, List.of(candidate(7L, "카페A")));
        var otherGap = new GapRecommendationService.Gap(99L, 100L, 35, List.of(candidate(8L, "카페B")));
        when(gapRecommendationService.findGaps(user, 10L, 2))
                .thenReturn(Optional.of(List.of(otherGap, matchingGap)));

        var call = new GeminiChatClient.FunctionCall("get_gap_recommendations", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).placeId()).isEqualTo(7L);
    }

    @Test
    void note_preference는_세션에_이미_보여준_placeId만_허용한다() throws Exception {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        state.addShownCandidateIds(List.of(5L));
        // Place()는 com.trova.backend.entity 패키지 내부에서만 쓸 수 있는 protected
        // 생성자라 이 테스트(다른 패키지)에서는 못 쓴다 — 공개 생성자로 대체한다.
        Place place = new Place("g-5", "장소5", "cafe", 4.5, 100, null, 37.5, 127.0, "서울");
        setId(place, 5L);
        when(placeRepository.findById(5L)).thenReturn(Optional.of(place));

        var call = new GeminiChatClient.FunctionCall("note_preference", Map.of("placeId", 5), "sig");
        executor.execute(user, state, call, "메시지");

        verify(userPreferenceSignalRepository).save(argThat(signal ->
                signal.getSignalType() == SignalType.CHAT_LIKED && signal.getPlace() == place));
        verify(placeEmbeddingService).ensureEmbeddings(List.of(place));
    }

    @Test
    void note_preference는_보여준적_없는_placeId면_거부하고_저장하지_않는다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        // shownCandidateIds가 비어있음 — placeId 9는 이 세션에서 보여준 적 없음.

        var call = new GeminiChatClient.FunctionCall("note_preference", Map.of("placeId", 9), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        verify(userPreferenceSignalRepository, never()).save(any());
        assertThat(result.responseForGemini()).containsKey("error");
    }

    @Test
    void 알수없는_도구_이름이면_에러_응답을_돌려준다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        var call = new GeminiChatClient.FunctionCall("delete_everything", Map.of(), "sig");

        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        assertThat(result.responseForGemini()).containsKey("error");
        assertThat(result.candidates()).isNull();
        verify(apiCallLogService).record(
                eq("internal"), eq("conversation-tool-delete_everything"), eq(null),
                anyLong(), eq(false), any(), eq(null), eq(null), eq(null));
    }

    @Test
    void 도구_실행_중_예외가_나면_500대신_에러_응답으로_폴백한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(alternativeFinderService.findAlternatives(any(), anyLong(), any()))
                .thenThrow(new RuntimeException("DB 오류"));

        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        ConversationToolExecutor.ToolExecutionResult result = executor.execute(user, state, call, "메시지");

        assertThat(result.responseForGemini()).containsKey("error");
        verify(apiCallLogService).record(
                eq("internal"), eq("conversation-tool-find_alternatives"), eq(null),
                anyLong(), eq(false), any(), eq(null), eq(null), eq(null));
    }
}
