package com.trova.backend.conversation;

import com.sun.net.httpserver.HttpServer;
import com.trova.backend.config.RestClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-09-30 운영 서버에서 Gemini가 잠시 느려진 동안 대화가 정확히 5초에 끊겼다(#69).
 * 공용 빌더(RestClientConfig)의 읽기 대기 5초를 넘는 응답(6초)을 가짜 서버로 재현한다.
 * MockRestServiceServer는 네트워크를 타지 않아 대기 시간을 검사할 수 없어서 실제 HTTP 서버를 띄운다.
 */
class GeminiChatClientTimeoutTest {

    private static final int SLOW_RESPONSE_MS = 6_000;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startSlowGemini() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(SLOW_RESPONSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = """
                    {"candidates":[{"content":{"parts":[{"text":"늦었지만 답해요"}],"role":"model"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 공용_빌더로는_6초_응답이_끊긴다_재현_확인() {
        var restClient = new RestClientConfig().restClientBuilder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> restClient.post().uri("/x").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }

    @Test
    void 대화_클라이언트는_5초가_넘는_Gemini_응답도_기다린다() {
        // 운영 생성자와 같은 HTTP 설정(chatRequestFactory)을 공용 빌더에 얹는다 — 주소만 가짜 서버로 바꾼다.
        var builder = new RestClientConfig().restClientBuilder()
                .requestFactory(GeminiChatClientImpl.chatRequestFactory());
        GeminiChatClientImpl client = new GeminiChatClientImpl("test-key", builder, baseUrl);

        GeminiChatClient.ChatResult result = client.sendMessage(List.of(), "근처 카페 추천해줘", List.of());

        assertThat(result.text()).isEqualTo("늦었지만 답해요");
    }
}
