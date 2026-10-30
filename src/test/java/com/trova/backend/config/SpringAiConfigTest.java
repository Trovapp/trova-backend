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
    void 만들어진_Gemini_클라이언트는_HTTP_1_1만_쓰고_전체_20초_제한만_둔다() {
        Client client = new SpringAiConfig().geminiClient("test-key");
        ApiClient apiClient = (ApiClient) ReflectionTestUtils.getField(client, "apiClient");
        OkHttpClient http = apiClient.httpClient();

        assertThat(http.protocols()).containsExactly(Protocol.HTTP_1_1);
        // 직접 넘긴 클라이언트에는 SDK가 httpOptions().timeout을 걸어 주지 않는다 — 전체 제한은 우리가 건 20초여야 한다.
        assertThat(http.callTimeoutMillis()).isEqualTo(20_000);
        // SDK가 직접 만들 때와 같게 연결·읽기·쓰기 제한은 0 — OkHttp 기본값 10초가 남으면 긴 초안 생성이 10초에 끊긴다.
        assertThat(http.connectTimeoutMillis()).isZero();
        assertThat(http.readTimeoutMillis()).isZero();
        assertThat(http.writeTimeoutMillis()).isZero();
    }
}
