package com.trova.backend.config;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Gemini 텍스트·JSON 호출을 Spring AI로(#243, ADR 0020). 자동 설정(GoogleGenAiChatAutoConfiguration)은 application.yml에서 끄고
 * 여기서 직접 만든다 — 자동 설정은 API 키가 비면 Vertex AI 쪽으로 가서 프로젝트 ID가 없다는 예외로 서버가 뜨지 않고,
 * 읽기 제한 시간을 속성으로 줄 수 없다.
 */
@Configuration
public class SpringAiConfig {

    // 장소 추출(pipeline-test/extract_places.py)·대화 비서와 같은 모델.
    public static final String MODEL = "gemini-3.5-flash-lite";
    // 기존 Gemini 클라이언트와 같은 20초 — 텍스트 생성 실측 평균 6.0초/최대 8.6초(2026-09-24)라 공용 5초로는 거의 항상 끊겼다.
    static final int READ_TIMEOUT_MS = 20_000;

    @Bean
    public Client geminiClient(@Value("${app.pipeline.gemini-api-key}") String apiKey) {
        // 키가 비어도(테스트·키 없는 로컬) 서버는 떠야 한다 — 실제 호출만 Gemini가 거절하고, 부르는 쪽은 실패로 처리한다.
        return Client.builder()
                .apiKey(apiKey == null || apiKey.isBlank() ? "unset" : apiKey)
                .httpOptions(httpOptions())
                .clientOptions(clientOptions())
                .build();
    }

    /**
     * google-genai는 OkHttp로 HTTP/2(ALPN)를 먼저 쓴다. 예전 클라이언트(SimpleClientHttpRequestFactory = HttpURLConnection, HTTP/1.1)는
     * 운영(Oracle E2.1.Micro)에서 잘 됐는데, Spring AI로 바꾼 뒤 운영에서만 호출이 전부 20초 시간 초과였다(2026-10-31, 6/6, #253).
     * 같은 시각 맥에서는 정상이라 Gemini 쪽 문제가 아니다. 예전과 같은 HTTP/1.1만 쓰도록 OkHttpClient를 직접 넘긴다.
     * SDK는 이 클라이언트를 newBuilder()로 이어받아 재시도 인터셉터만 덧붙이고, 직접 만들 때 두는 설정(연결·읽기·쓰기 제한 0,
     * 전체 제한 callTimeout = httpOptions.timeout)은 모두 건너뛴다(ApiClient.createHttpClient, 1.65.0 바이트코드 확인). 요청 단위 제한은
     * 요청에 HttpOptions가 붙을 때만 거는데 Spring AI는 붙이지 않는다. 그래서 전체 20초를 여기서 직접 걸고, 연결·읽기·쓰기는 SDK 기본처럼
     * 0으로 둔다 — OkHttp 기본값 10초가 남으면 긴 초안 생성이 20초가 아니라 10초에 끊긴다.
     */
    static ClientOptions clientOptions() {
        return ClientOptions.builder().customHttpClient(http1Client()).build();
    }

    static OkHttpClient http1Client() {
        return new OkHttpClient.Builder()
                .protocols(List.of(Protocol.HTTP_1_1))
                .callTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO)
                .build();
    }

    /**
     * google-genai SDK는 따로 설정하지 않아도 408·429·5xx와 시간 초과에 최대 5회 다시 보낸다(RetryInterceptor 기본값, 라이브러리 코드 확인).
     * 그대로 두면 GeminiJsonClientImpl의 3회와 곱해져 한 요청이 최대 15회 호출되고, 시간 초과 한 번이 20초 × 5로 늘어난다
     * (실측: 제한 20초인데 한 호출이 41초). 재시도는 GeminiJsonClientImpl 한 곳에서만 하도록 1회로 고정한다.
     */
    static HttpOptions httpOptions() {
        return HttpOptions.builder()
                .timeout(READ_TIMEOUT_MS)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                .build();
    }

    @Bean
    public GoogleGenAiChatModel geminiChatModel(Client geminiClient, ObjectProvider<ObservationRegistry> observationRegistry) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(geminiClient)
                .options(GoogleGenAiChatOptions.builder().model(MODEL).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                // Spring AI 기본 재시도는 최대 10회라 한 요청이 하루 한도를 10배로 쓸 수 있다. 재시도는 GeminiJsonClientImpl이 3회까지만 한다.
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                // 호출 시간·토큰 지표(gen_ai.client.*)를 Prometheus로. 프롬프트·응답 본문은 기본값대로 남기지 않는다.
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }
}
