package com.trova.backend.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiTextClientImplTest {

    @Test
    void 응답_글을_앞뒤_공백_없이_돌려준다() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  조용한 골목 카페예요 \n")))));

        assertThat(new GeminiTextClientImpl(chatModel).generate("p")).contains("조용한 골목 카페예요");
    }

    @Test
    void 호출이_실패하면_빈_값이다() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Gemini 429"));

        assertThat(new GeminiTextClientImpl(chatModel).generate("p")).isEmpty();
    }
}
