package com.trova.backend.config;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiConfigTest {

    @Test
    void Gemini_SDK는_다시_보내지_않고_20초에서_끊는다() {
        HttpOptions options = SpringAiConfig.httpOptions();

        assertThat(options.timeout()).contains(20_000);
        assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
    }

    @Test
    void 만들어진_Gemini_클라이언트는_HTTP_1_1만_쓰고_연결_읽기_쓰기_제한을_따로_두지_않는다() {
        Client client = new SpringAiConfig().geminiClient("test-key");
        ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");
        OkHttpClient http = apiClient.httpClient();

        assertThat(http.protocols()).containsExactly(Protocol.HTTP_1_1);
        // 20초는 SDK가 요청마다 httpOptions().timeout으로 전체 제한(callTimeout)을 건다(위 테스트). 여기서는 SDK가 직접 만들 때와 같게
        // 연결·읽기·쓰기 제한을 0으로 둔다 — OkHttp 기본값 10초가 남으면 긴 초안 생성이 20초가 아니라 10초에 끊긴다.
        assertThat(http.connectTimeoutMillis()).isZero();
        assertThat(http.readTimeoutMillis()).isZero();
        assertThat(http.writeTimeoutMillis()).isZero();
    }
}
