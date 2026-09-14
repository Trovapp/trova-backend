package com.trova.backend.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Component
public class GeminiEmbeddingClientImpl implements GeminiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClientImpl.class);
    private static final int OUTPUT_DIMENSIONALITY = 768;

    private final String apiKey;
    private final RestClient restClient;

    public GeminiEmbeddingClientImpl(
            @Value("${app.pipeline.gemini-api-key}") String apiKey,
            RestClient.Builder restClientBuilder
    ) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    private record EmbedRequest(String taskType, Content content, int output_dimensionality) {
        record Content(List<Part> parts) {
        }
        record Part(String text) {
        }
    }

    // 실제 API 응답은 "embeddings"(복수, 배열)가 아니라 "embedding"(단수, 객체) —
    // 2026-09-14에 실제 호출로 확인함. 공식 문서 기반으로 작성했던 이전 형태(복수형)는
    // 응답을 매번 null로 역직렬화시켜서 임베딩 생성이 전부 조용히 실패하고 있었다.
    private record EmbedResponse(Embedding embedding) {
        record Embedding(List<Double> values) {
        }
    }

    @Override
    public Optional<float[]> embed(String text) {
        try {
            EmbedRequest request = new EmbedRequest(
                    "SEMANTIC_SIMILARITY",
                    new EmbedRequest.Content(List.of(new EmbedRequest.Part(text))),
                    OUTPUT_DIMENSIONALITY);

            EmbedResponse response = restClient.post()
                    .uri("/v1beta/models/gemini-embedding-001:embedContent")
                    .header("x-goog-api-key", apiKey)
                    .body(request)
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.embedding() == null || response.embedding().values() == null
                    || response.embedding().values().isEmpty()) {
                log.warn("Gemini 임베딩 응답이 비어있습니다");
                return Optional.empty();
            }
            List<Double> values = response.embedding().values();
            return Optional.of(normalize(values));
        } catch (Exception e) {
            log.warn("Gemini 임베딩 생성 실패", e);
            return Optional.empty();
        }
    }

    /** output_dimensionality가 모델 기본값(3072)보다 작을 때는 API 문서상 수동 L2 정규화가 필요하다. */
    private float[] normalize(List<Double> values) {
        double sumSquares = 0.0;
        for (double v : values) {
            sumSquares += v * v;
        }
        double norm = Math.sqrt(sumSquares);
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = norm > 0 ? (float) (values.get(i) / norm) : 0f;
        }
        return result;
    }
}
