package com.trova.backend.planner;

import com.trova.backend.service.ApiCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class GeminiJsonClientImpl implements GeminiJsonClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiJsonClientImpl.class);
    // 장소 추출(pipeline-test/extract_places.py)·대화 비서와 같은 모델.
    static final String MODEL = "gemini-3.5-flash-lite";
    static final String BASE_URL = "https://generativelanguage.googleapis.com";
    static final int MAX_ATTEMPTS = 3;
    // 파이프라인과 같은 원칙(#7): 지수 대기에 상한을 둬 한 요청이 대기만으로 오래 붙잡히지 않게 한다.
    static final long BASE_DELAY_MS = 2_000;
    static final long MAX_DELAY_MS = 30_000;

    private final String apiKey;
    private final RestClient restClient;
    private final ApiCallLogService apiCallLogService;
    private final Sleeper sleeper;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public GeminiJsonClientImpl(
            @Value("${app.pipeline.gemini-api-key}") String apiKey,
            RestClient.Builder restClientBuilder,
            ApiCallLogService apiCallLogService
    ) {
        this(apiKey, restClientBuilder.requestFactory(requestFactory()), BASE_URL, apiCallLogService, Thread::sleep);
    }

    GeminiJsonClientImpl(String apiKey, RestClient.Builder builder, String baseUrl,
                         ApiCallLogService apiCallLogService, Sleeper sleeper) {
        this.apiKey = apiKey;
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiCallLogService = apiCallLogService;
        this.sleeper = sleeper;
    }

    // 공용 빌더의 읽기 대기 5초로는 짧다 — 다른 Gemini 클라이언트와 같은 20초.
    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return factory;
    }

    record Response(List<Candidate> candidates, Usage usageMetadata) {
        record Candidate(Content content) {
        }

        record Content(List<Part> parts) {
        }

        record Part(String text) {
        }

        record Usage(Integer promptTokenCount, Integer candidatesTokenCount, Integer totalTokenCount) {
        }
    }

    @Override
    public Optional<String> generateJson(String prompt, String operation) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature", 0.1, "responseMimeType", "application/json"));
        long start = System.currentTimeMillis();
        String lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Response response = restClient.post()
                        .uri("/v1beta/models/{model}:generateContent", MODEL)
                        .header("x-goog-api-key", apiKey)
                        .body(body)
                        .retrieve()
                        .body(Response.class);
                Response.Usage usage = response == null ? null : response.usageMetadata();
                apiCallLogService.record("gemini", operation, null, System.currentTimeMillis() - start, true, null,
                        usage == null ? null : usage.promptTokenCount(),
                        usage == null ? null : usage.candidatesTokenCount(),
                        usage == null ? null : usage.totalTokenCount());
                return text(response);
            } catch (HttpStatusCodeException e) {
                lastError = "Gemini " + e.getStatusCode().value();
                boolean retryable = e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
                if (!retryable || attempt == MAX_ATTEMPTS - 1) {
                    break;
                }
                if (!pause(attempt)) {
                    break;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt == MAX_ATTEMPTS - 1 || !pause(attempt)) {
                    break;
                }
            }
        }
        apiCallLogService.record("gemini", operation, null, System.currentTimeMillis() - start, false, lastError,
                null, null, null);
        log.warn("Gemini JSON 생성 실패(operation={}): {}", operation, lastError);
        return Optional.empty();
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

    private static Optional<String> text(Response response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return Optional.empty();
        }
        Response.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(content.parts().get(0).text()).map(String::trim).filter(t -> !t.isEmpty());
    }
}
