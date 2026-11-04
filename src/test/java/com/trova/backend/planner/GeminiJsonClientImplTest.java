package com.trova.backend.planner;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.trova.backend.entity.ApiCallLog;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(true), isNull(), eq(120), eq(8), eq(128), eq(2), anyLong(), any());
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
        verify(logs).record(eq("gemini"), eq("trip-plan.test"), isNull(), anyLong(), eq(false), eq("Gemini 400"), isNull(), isNull(), isNull(), eq(1), eq(0L));
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

    @Test
    void 첫_성공은_시도1회와_대기0으로_기록한다() {
        ChatModel model = mock(ChatModel.class);
        ApiCallLogService logs = mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenReturn(ok("{}"));
        GeminiJsonClientImpl client = new GeminiJsonClientImpl(model, logs, millis -> { throw new AssertionError("no wait"); }, () -> 0);
        assertThat(client.generateJson("p", "test")).contains("{}");
        verify(logs).record(eq("gemini"), eq("test"), isNull(), anyLong(), eq(true), isNull(), eq(120), eq(8), eq(128), eq(1), eq(0L), any());
    }

    @Test
    void 세번째_성공은_실제_시도수와_누적_대기를_함께_기록한다() {
        ChatModel model = mock(ChatModel.class);
        ApiCallLogService logs = mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenThrow(gemini(503)).thenThrow(gemini(429)).thenReturn(ok("{}"));
        AtomicLong clock = new AtomicLong();
        GeminiJsonClientImpl client = new GeminiJsonClientImpl(model, logs,
                millis -> clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)), clock::get);
        assertThat(client.generateJson("p", "test")).contains("{}");
        verify(model,times(3)).call(any(Prompt.class));
        verify(logs).record(eq("gemini"),eq("test"),isNull(),anyLong(),eq(true),isNull(),eq(120),eq(8),eq(128),eq(3),eq(6000L), any());
    }

    @Test
    void 최종_실패도_실제_시도수와_대기를_기록한다() {
        ChatModel model=mock(ChatModel.class);
        ApiCallLogService logs=mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenThrow(gemini(503));
        AtomicLong clock=new AtomicLong();
        GeminiJsonClientImpl client=new GeminiJsonClientImpl(model,logs,
                millis->clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)),clock::get);
        assertThat(client.generateJson("p","test")).isEmpty();
        verify(logs).record(eq("gemini"),eq("test"),isNull(),anyLong(),eq(false),eq("Gemini 503"),isNull(),isNull(),isNull(),eq(3),eq(6000L));
    }

    @Test
    void 요청한_대기가_아니라_실제로_지난_시간을_기록한다() {
        ChatModel model=mock(ChatModel.class);
        ApiCallLogService logs=mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenThrow(gemini(429)).thenReturn(ok("{}"));
        AtomicLong clock=new AtomicLong();
        GeminiJsonClientImpl client=new GeminiJsonClientImpl(model,logs,
                millis->clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1250)),clock::get);
        client.generateJson("p","test");
        verify(logs).record(eq("gemini"),eq("test"),isNull(),anyLong(),eq(true),isNull(),eq(120),eq(8),eq(128),eq(2),eq(1250L), any());
    }

    @Test
    void 대기_인터럽트는_일부_대기시간을_기록하고_다시_호출하지_않는다() {
        ChatModel model=mock(ChatModel.class);
        ApiCallLogService logs=mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenThrow(gemini(429));
        AtomicLong clock=new AtomicLong();
        GeminiJsonClientImpl client=new GeminiJsonClientImpl(model,logs,millis->{
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(750));throw new InterruptedException("fixture");
        },clock::get);
        try {
            assertThat(client.generateJson("p","test")).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(model,times(1)).call(any(Prompt.class));
            verify(logs).record(eq("gemini"),eq("test"),isNull(),anyLong(),eq(false),eq("Gemini 429"),isNull(),isNull(),isNull(),eq(1),eq(750L));
        } finally { Thread.interrupted(); }
    }

    @Test
    void 성공_후_기록저장이_실패해도_모델을_다시_호출하지_않는다() {
        ChatModel model=mock(ChatModel.class);
        ApiCallLogService logs=mock(ApiCallLogService.class);
        when(model.call(any(Prompt.class))).thenReturn(ok("{}"));
        doThrow(new IllegalStateException("log fixture")).when(logs).record(eq("gemini"),eq("test"),isNull(),anyLong(),eq(true),isNull(),eq(120),eq(8),eq(128),eq(1),eq(0L), any());
        GeminiJsonClientImpl client=new GeminiJsonClientImpl(model,logs,millis->{throw new AssertionError("no retry");},()->0);
        assertThatThrownBy(()->client.generateJson("p","test")).isInstanceOf(IllegalStateException.class).hasMessage("log fixture");
        verify(model,times(1)).call(any(Prompt.class));
    }

    @Test
    void 생각_토큰과_캐시_토큰을_호출_기록에_넘긴다() {
        // #317: 일정 생성 등 Java 호출도 생각 토큰을 쓰는지 본다. 텍스트만 보내므로 영상 토큰은 없다.
        GoogleGenAiUsage usage = GoogleGenAiUsage.from(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(120).candidatesTokenCount(8).totalTokenCount(158)
                .thoughtsTokenCount(30).cachedContentTokenCount(64).build());

        ApiCallLog.TokenDetail detail = GeminiJsonClientImpl.tokenDetail(usage);

        assertThat(detail).isEqualTo(new ApiCallLog.TokenDetail(30, 64, null));
        assertThat(GeminiJsonClientImpl.tokenDetail(new DefaultUsage(1, 2))).isNull();
        assertThat(GeminiJsonClientImpl.tokenDetail(null)).isNull();
    }
}
