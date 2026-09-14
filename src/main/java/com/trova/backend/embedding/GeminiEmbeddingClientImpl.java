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

    private record EmbedResponse(List<Embedding> embeddings) {
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

            if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
                log.warn("Gemini 임베딩 응답이 비어있습니다");
                return Optional.empty();
            }
            List<Double> values = response.embeddings().get(0).values();
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
