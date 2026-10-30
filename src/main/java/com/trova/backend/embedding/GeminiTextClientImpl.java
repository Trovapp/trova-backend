package com.trova.backend.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 짧은 문장 생성. 요청·응답 형식, 모델 이름, 20초 읽기 제한은 SpringAiConfig의 ChatModel이 맡는다(#243) —
 * 예전엔 이 클래스가 Gemini REST 요청·응답 레코드와 타임아웃을 직접 만들었다.
 */
@Component
public class GeminiTextClientImpl implements GeminiTextClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTextClientImpl.class);

    private final ChatModel chatModel;

    public GeminiTextClientImpl(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Optional<String> generate(String prompt) {
        try {
            return text(chatModel.call(new Prompt(prompt)));
        } catch (Exception e) {
            log.warn("Gemini 텍스트 생성 실패", e);
            return Optional.empty();
        }
    }

    static Optional<String> text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.getResult().getOutput().getText()).map(String::trim);
    }
}
