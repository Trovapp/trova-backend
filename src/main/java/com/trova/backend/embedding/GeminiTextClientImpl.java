package com.trova.backend.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
        // 타임아웃은 RestClientConfig의 prototype RestClient.Builder 빈이 이미 설정한다
        // (connect 3s / read 5s) — 여기서 별도 requestFactory를 만들지 않는다.
        this.restClient = restClientBuilder
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
