package com.trova.backend.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.client.ExpectedCount.once;

class GeminiEmbeddingClientImplTest {

    @Test
    void 정상_응답이면_정규화된_임베딩을_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"embedding": {"values": [3.0, 4.0]}}
                        """, MediaType.APPLICATION_JSON));

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);
        Optional<float[]> result = client.embed("카페, 조용한 분위기");

        assertThat(result).isPresent();
        // 3,4 벡터의 L2 노름은 5 — 정규화하면 [0.6, 0.8]
        assertThat(result.get()[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(result.get()[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void 응답에_embedding_필드가_없으면_빈값을_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);
        Optional<float[]> result = client.embed("텍스트");

        assertThat(result).isEmpty();
    }

    @Test
    void 호출_실패하면_빈값을_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
                .andRespond(withServerError());

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);
        Optional<float[]> result = client.embed("텍스트");

        assertThat(result).isEmpty();
    }

    private static final String BATCH_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents";

    @Test
    void 여러_문장을_요청_한_번으로_보내고_순서대로_정규화된_임베딩을_반환한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(BATCH_URL))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[0].model").value("models/gemini-embedding-001"))
                .andExpect(jsonPath("$.requests[0].content.parts[0].text").value("카페"))
                .andExpect(jsonPath("$.requests[1].content.parts[0].text").value("공원"))
                .andExpect(jsonPath("$.requests[0].taskType").value("SEMANTIC_SIMILARITY"))
                .andExpect(jsonPath("$.requests[0].output_dimensionality").value(768))
                .andRespond(withSuccess("""
                        {"embeddings": [{"values": [3.0, 4.0]}, {"values": [0.0, 2.0]}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);
        Optional<List<float[]>> result = client.embedBatch(List.of("카페", "공원"));

        server.verify();
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0)[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(result.get().get(1)[1]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void 묶음_응답_개수가_요청과_다르면_전부_빈값이다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BATCH_URL))
                .andRespond(withSuccess("""
                        {"embeddings": [{"values": [3.0, 4.0]}]}
                        """, MediaType.APPLICATION_JSON));

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);

        assertThat(client.embedBatch(List.of("카페", "공원"))).isEmpty();
    }

    @Test
    void 묶음_호출이_실패하면_빈값이다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BATCH_URL)).andRespond(withServerError());

        GeminiEmbeddingClientImpl client = new GeminiEmbeddingClientImpl("test-key", builder);

        assertThat(client.embedBatch(List.of("카페"))).isEmpty();
    }
}
