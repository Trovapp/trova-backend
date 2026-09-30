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
    private static final String MODEL = "gemini-embedding-001";

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

    private record BatchEmbedRequest(List<BatchItem> requests) {
        record BatchItem(String model, String taskType, EmbedRequest.Content content, int output_dimensionality) {
        }
    }

    private record BatchEmbedResponse(List<EmbedResponse.Embedding> embeddings) {
    }

    // 새 장소를 하나씩 embedContent로 부르면 19개에 약 9.9초, 한 번에 묶으면 1.3초였다(#73, 로컬 Mac 실측,
    // 결과 벡터는 같음). 무료 한도는 묶음 안의 문장 수로 세므로 사용량은 같다.
    @Override
    public Optional<List<float[]>> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return Optional.of(List.of());
        }
        try {
            List<BatchEmbedRequest.BatchItem> items = texts.stream()
                    .map(text -> new BatchEmbedRequest.BatchItem(
                            "models/" + MODEL, "SEMANTIC_SIMILARITY",
                            new EmbedRequest.Content(List.of(new EmbedRequest.Part(text))),
                            OUTPUT_DIMENSIONALITY))
                    .toList();

            BatchEmbedResponse response = restClient.post()
                    .uri("/v1beta/models/" + MODEL + ":batchEmbedContents")
                    .header("x-goog-api-key", apiKey)
                    .body(new BatchEmbedRequest(items))
                    .retrieve()
                    .body(BatchEmbedResponse.class);

            if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
                log.warn("Gemini 묶음 임베딩 응답 개수가 요청과 다릅니다: 요청 {}개", texts.size());
                return Optional.empty();
            }
            List<float[]> result = new java.util.ArrayList<>();
            for (EmbedResponse.Embedding embedding : response.embeddings()) {
                if (embedding == null || embedding.values() == null || embedding.values().isEmpty()) {
                    log.warn("Gemini 묶음 임베딩 응답에 빈 항목이 있습니다");
                    return Optional.empty();
                }
                result.add(normalize(embedding.values()));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("Gemini 묶음 임베딩 생성 실패: {}개", texts.size(), e);
            return Optional.empty();
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
