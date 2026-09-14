package com.trova.backend.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

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
}
