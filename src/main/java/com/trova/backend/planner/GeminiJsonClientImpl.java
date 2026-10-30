package com.trova.backend.planner;

import com.google.genai.errors.ApiException;
import com.trova.backend.config.SpringAiConfig;
import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 요청·응답 형식과 읽기 제한(20초)은 SpringAiConfig의 ChatModel이 맡고(#243), 여기는 재시도·호출 기록만 한다.
 * Spring AI 자체 재시도는 꺼 두었다 — 재시도 횟수는 여기 MAX_ATTEMPTS 하나로만 정한다.
 */
@Component
public class GeminiJsonClientImpl implements GeminiJsonClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiJsonClientImpl.class);
    static final int MAX_ATTEMPTS = 3;
    // 파이프라인과 같은 원칙(#7): 지수 대기에 상한을 둬 한 요청이 대기만으로 오래 붙잡히지 않게 한다.
    static final long BASE_DELAY_MS = 2_000;
    static final long MAX_DELAY_MS = 30_000;
    static final GoogleGenAiChatOptions JSON_OPTIONS = GoogleGenAiChatOptions.builder()
            .model(SpringAiConfig.MODEL)
            .temperature(0.1)
            .responseMimeType("application/json")
            .build();

    private final ChatModel chatModel;
    private final ApiCallLogService apiCallLogService;
    private final Sleeper sleeper;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public GeminiJsonClientImpl(ChatModel chatModel, ApiCallLogService apiCallLogService) {
        this(chatModel, apiCallLogService, Thread::sleep);
    }

    GeminiJsonClientImpl(ChatModel chatModel, ApiCallLogService apiCallLogService, Sleeper sleeper) {
        this.chatModel = chatModel;
        this.apiCallLogService = apiCallLogService;
        this.sleeper = sleeper;
    }

    @Override
    public Optional<String> generateJson(String prompt, String operation) {
        long start = System.currentTimeMillis();
        String lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                ChatResponse response = chatModel.call(new Prompt(prompt, JSON_OPTIONS));
                Usage usage = response == null ? null : response.getMetadata().getUsage();
                apiCallLogService.record("gemini", operation, null, System.currentTimeMillis() - start, true, null,
                        usage == null ? null : usage.getPromptTokens(),
                        usage == null ? null : usage.getCompletionTokens(),
                        usage == null ? null : usage.getTotalTokens());
                return text(response);
            } catch (Exception e) {
                Integer status = statusCode(e);
                lastError = status != null ? "Gemini " + status : e.getClass().getSimpleName() + ": " + e.getMessage();
                // 상태 코드가 없는 오류(시간 초과·연결 끊김)는 예전처럼 다시 시도하고, 4xx는 다시 보내도 같아서 멈춘다(429만 예외).
                boolean retryable = status == null || status == 429 || status >= 500;
                if (!retryable || attempt == MAX_ATTEMPTS - 1 || !pause(attempt)) {
                    break;
                }
            }
        }
        apiCallLogService.record("gemini", operation, null, System.currentTimeMillis() - start, false, lastError,
                null, null, null);
        log.warn("Gemini JSON 생성 실패(operation={}): {}", operation, lastError);
        return Optional.empty();
    }

    /** google-genai가 던진 HTTP 오류의 상태 코드. Spring AI가 RuntimeException으로 감싸서 원인 사슬을 따라간다. */
    static Integer statusCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) {
                return api.code();
            }
        }
        return null;
    }

    private boolean pause(int attempt) {
        try {
            sleeper.sleep(Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1L << attempt)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Optional<String> text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.getResult().getOutput().getText()).map(String::trim).filter(t -> !t.isEmpty());
    }
}
