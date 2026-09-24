package com.trova.backend.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class GeminiTextClientImpl implements GeminiTextClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiTextClientImpl.class);
    // pipeline-test/extract_places.py의 DEFAULT_MODEL과 동일한 값(확인함, 2026-09-09
    // 기준) — 이 프로젝트가 실제로 쓰는 생성 모델과 통일한다.
    private static final String MODEL = "gemini-3.5-flash-lite";

    private final String apiKey;
    private final RestClient restClient;

    public GeminiTextClientImpl(
            @Value("${app.pipeline.gemini-api-key}") String apiKey,
            RestClient.Builder restClientBuilder
    ) {
        this.apiKey = apiKey;
        // RestClientConfig의 공용 prototype 빈은 읽기 타임아웃 5초 — 카카오/날씨 같은 빠른
        // REST API엔 맞지만, Gemini 텍스트 생성은 실측(api_call_logs, operation
        // "conversation-tool-find_alternatives") 평균 6.0초/최대 8.6초라 5초로는 거의
        // 항상 타임아웃난다(2026-09-24 실측 — 대안 찾기가 SocketTimeoutException으로 계속
        // 실패하는 걸 로그로 확인). 이 클라이언트만 별도 타임아웃을 준다.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    private record GenerateRequest(List<Content> contents) {
        record Content(List<Part> parts) {
        }
        record Part(String text) {
        }
    }

    private record GenerateResponse(List<Candidate> candidates) {
        record Candidate(Content content) {
        }
        record Content(List<Part> parts) {
        }
        record Part(String text) {
        }
    }

    @Override
    public Optional<String> generate(String prompt) {
        try {
            GenerateRequest request = new GenerateRequest(
                    List.of(new GenerateRequest.Content(List.of(new GenerateRequest.Part(prompt)))));

            GenerateResponse response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", MODEL)
                    .header("x-goog-api-key", apiKey)
                    .body(request)
                    .retrieve()
                    .body(GenerateResponse.class);

            if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
                return Optional.empty();
            }
            List<GenerateResponse.Part> parts = response.candidates().get(0).content().parts();
            if (parts == null || parts.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(parts.get(0).text()).map(String::trim);
        } catch (Exception e) {
            log.warn("Gemini 텍스트 생성 실패", e);
            return Optional.empty();
        }
    }
}
