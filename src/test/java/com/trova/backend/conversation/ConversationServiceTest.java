package com.trova.backend.conversation;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private GeminiChatClient geminiChatClient;
    @Mock private ConversationToolExecutor toolExecutor;
    @Mock private ApiCallLogService apiCallLogService;

    private ConversationService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ConversationService(geminiChatClient, toolExecutor, apiCallLogService);
        user = new User("google", "u1", "테스트유저", null);
    }

    private AlternativeCandidate candidate(Long placeId) {
        return new AlternativeCandidate(placeId, "g-" + placeId, "카페", "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
    }

    @Test
    void 도구_호출_없이_텍스트만_오면_그대로_답변한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), eq("안녕"), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요! 뭘 도와드릴까요?"));

        ConversationService.TurnResult result = service.sendMessage(user, state, "안녕");

        assertThat(result.reply()).isEqualTo("안녕하세요! 뭘 도와드릴까요?");
        assertThat(result.candidates()).isNull();
        assertThat(result.turnCount()).isEqualTo(1);
        assertThat(result.turnLimitReached()).isFalse();
        assertThat(state.getHistory()).hasSize(2);
        verify(toolExecutor, never()).execute(any(), any(), any(), any());
    }

    @Test
    void functionCall이_오면_도구를_실행하고_결과를_반영해_재호출한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        var functionCall = new GeminiChatClient.FunctionCall("find_alternatives", Map.of("category", "카페"), "sig");
        when(geminiChatClient.sendMessage(any(), eq("조용한 카페 찾아줘"), any()))
                .thenReturn(new GeminiChatClient.ChatResult(functionCall, null));

        List<AlternativeCandidate> candidates = List.of(candidate(5L));
        var toolResult = new ConversationToolExecutor.ToolExecutionResult(candidates, Map.of("candidates", List.of()));
        when(toolExecutor.execute(user, state, functionCall, "조용한 카페 찾아줘")).thenReturn(toolResult);

        when(geminiChatClient.sendFunctionResult(any(), eq("조용한 카페 찾아줘"), eq(functionCall), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "커피한약방을 추천해요"));

        ConversationService.TurnResult result = service.sendMessage(user, state, "조용한 카페 찾아줘");

        assertThat(result.reply()).isEqualTo("커피한약방을 추천해요");
        assertThat(result.candidates()).isEqualTo(candidates);
        assertThat(state.getShownCandidateIds()).contains(5L);

        // 히스토리에 user 메시지, model 답변, 그리고 후보 요약(placeId 포함) 순으로
        // 남아야 다음 턴에서 note_preference가 placeId를 알 수 있다(Fix 2).
        assertThat(state.getHistory()).hasSize(3);
        assertThat(state.getHistory().get(0).role()).isEqualTo(ConversationState.ROLE_USER);
        assertThat(state.getHistory().get(0).text()).isEqualTo("조용한 카페 찾아줘");
        assertThat(state.getHistory().get(1).role()).isEqualTo(ConversationState.ROLE_MODEL);
        assertThat(state.getHistory().get(1).text()).isEqualTo("커피한약방을 추천해요");
        assertThat(state.getHistory().get(2).role()).isEqualTo(ConversationState.ROLE_MODEL);
        assertThat(state.getHistory().get(2).text()).contains("5=카페");

        // sendMessage와 sendFunctionResult는 서로 다른 Gemini 호출이므로 각각
        // conversation-turn으로 따로 기록되어야 한다(Fix 3).
        verify(apiCallLogService, times(2)).record(
                eq("gemini"), eq("conversation-turn"), eq(null), anyLong(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void 턴_상한에_도달하면_Gemini를_호출하지_않는다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        for (int i = 0; i < 10; i++) {
            state.incrementTurnCount();
        }

        ConversationService.TurnResult result = service.sendMessage(user, state, "한번더");

        assertThat(result.turnLimitReached()).isTrue();
        verify(geminiChatClient, never()).sendMessage(any(), any(), any());
    }

    @Test
    void Gemini_호출_실패하면_폴백_문구로_응답한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), anyString(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, null));

        ConversationService.TurnResult result = service.sendMessage(user, state, "안녕");

        assertThat(result.reply()).contains("다시 시도");
        assertThat(result.turnLimitReached()).isFalse();
    }

    @Test
    void 매턴마다_ApiCallLogService에_conversation_turn으로_기록한다() {
        ConversationState state = new ConversationState(1L, 10L, 100L, null, null);
        when(geminiChatClient.sendMessage(any(), anyString(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "답변"));

        service.sendMessage(user, state, "안녕");

        verify(apiCallLogService).record(
                eq("gemini"), eq("conversation-turn"), eq(null), anyLong(), anyBoolean(), any(), any(), any(), any());
    }
}
