package com.trova.backend.planner;

import com.google.genai.errors.ApiException;
import com.trova.backend.service.ApiCallLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiJsonClientImplTest {

    private static ChatResponse ok(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(120, 8, 128)).build())
                .build();
    }

    // Spring AI는 google-genai 오류를 RuntimeException으로 감싸 던진다.
    private static RuntimeException gemini(int code) {
        return new RuntimeException("Gemini 호출 실패", new ApiException(code, "STATUS", "message"));
    }

    @Test
    void JSON_응답을_요청하고_429면_기다렸다_다시_시도한다() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(gemini(429)).thenReturn(ok("{\"days\": 2}"));
        ApiCallLogService logs = mock(ApiCallLogService.class);
        List<Long> sleeps = new ArrayList<>();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl(chatModel, logs, sleeps::add);

        assertThat(client.generateJson("p", "trip-plan.test")).contains("{\"days\": 2}");
        assertThat(sleeps).containsExactly(2_000L);
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(true), isNull(), eq(120), eq(8), eq(128));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getTemperature()).isEqualTo(0.1);
    }

    @Test
    void 다시_시도해도_소용없는_오류는_바로_실패로_기록한다() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(gemini(400));
        ApiCallLogService logs = mock(ApiCallLogService.class);
        List<Long> sleeps = new ArrayList<>();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl(chatModel, logs, sleeps::add);

        assertThat(client.generateJson("p", "trip-plan.test")).isEmpty();
        assertThat(sleeps).isEmpty();
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(false), eq("Gemini 400"), isNull(), isNull(), isNull());
    }

    @Test
    void 상태_코드_없는_오류는_세_번까지만_시도한다() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        List<Long> sleeps = new ArrayList<>();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl(chatModel, mock(ApiCallLogService.class), sleeps::add);

        assertThat(client.generateJson("p", "trip-plan.test")).isEmpty();
        verify(chatModel, times(GeminiJsonClientImpl.MAX_ATTEMPTS)).call(any(Prompt.class));
        assertThat(sleeps).containsExactly(2_000L, 4_000L);
    }

    @Test
    void 대기_시간은_30초를_넘지_않는다() {
        // 상한 확인: 2초 × 2^n 이 30초를 넘는 회차에서도 30초
        assertThat(Math.min(GeminiJsonClientImpl.MAX_DELAY_MS, GeminiJsonClientImpl.BASE_DELAY_MS * (1L << 5))).isEqualTo(30_000L);
    }
}
