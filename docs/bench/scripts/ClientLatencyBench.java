package com.trova.backend.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.config.SpringAiConfig;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 두 클라이언트의 정상 JSON 요청 비교. 서버·DB·대기열은 실행하지 않는다. */
public class ClientLatencyBench {
    public static void main(String[] args) throws Exception {
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int pauseMillis = args.length > 2 ? Integer.parseInt(args[2]) : 6_000;
        if (samples < 1 || samples > 40 || pauseMillis < 6_000) {
            throw new IllegalArgumentException("samples must be 1..40; pause must be >= 6000ms");
        }
        String key = System.getenv("TROVA_BENCH_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("TROVA_BENCH_KEY required");
        ObjectMapper mapper = new ObjectMapper();
        String text = "다음 JSON 객체만 반환하세요: {\"ok\":true}";
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(20));
        RestClient rest = RestClient.builder().requestFactory(factory)
                .baseUrl("https://generativelanguage.googleapis.com").build();
        SpringAiConfig config = new SpringAiConfig();
        com.google.genai.Client client = config.geminiClient(key);
        org.springframework.ai.google.genai.GoogleGenAiChatModel model = config.geminiChatModel(client,
                new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(SpringAiConfig.MODEL)
                .temperature(0.1).responseMimeType("application/json").build();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int round = 0; round <= samples; round++) {
            // 순서 편향을 줄이기 위해 라운드마다 먼저 호출하는 클라이언트를 바꾼다.
            List<String> order = round % 2 == 0 ? List.of("rest", "spring-ai") : List.of("spring-ai", "rest");
            for (String implementation : order) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("client", implementation);
                row.put("warmup", round == 0);
                row.put("round", round);
                long start = System.nanoTime();
                try {
                    String response;
                    if (implementation.equals("rest")) {
                        String raw = rest.post().uri("/v1beta/models/{model}:generateContent", SpringAiConfig.MODEL)
                                .header("x-goog-api-key", key)
                                .body(Map.of("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", text)))),
                                        "generationConfig", Map.of("temperature", 0.1, "responseMimeType", "application/json")))
                                .retrieve().body(String.class);
                        com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(raw);
                        response = parsed.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
                        row.put("tokens", parsed.path("usageMetadata").path("totalTokenCount").asInt());
                    } else {
                        org.springframework.ai.chat.model.ChatResponse result = model.call(new Prompt(text, options));
                        response = result.getResult().getOutput().getText();
                        row.put("tokens", result.getMetadata().getUsage().getTotalTokens());
                    }
                    row.put("success", mapper.readTree(response).path("ok").asBoolean(false));
                } catch (Exception error) {
                    // 예외 메시지 대신 클래스명만 기록해 자격 정보 노출을 피한다.
                    row.put("success", false);
                    row.put("errorType", error.getClass().getSimpleName());
                    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                        if (cause instanceof com.google.genai.errors.ApiException api) row.put("httpStatus", api.code());
                        if (cause instanceof org.springframework.web.client.HttpStatusCodeException http) row.put("httpStatus", http.getStatusCode().value());
                    }
                }
                row.put("milliseconds", (System.nanoTime() - start) / 1_000_000.0);
                rows.add(row);
                Path output = Path.of(args[0]);
                Files.createDirectories(output.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                        "environment", "local Mac; no server/DB/queue; sequential alternating order",
                        "model", SpringAiConfig.MODEL, "prompt", text,
                        "scope", "client invocations; application/library retries disabled; round 0 excluded as warmup",
                        "samplesPerClient", samples, "pauseMillis", pauseMillis,
                        "wireAttemptsObserved", false,
                        "results", rows));
                System.out.println(implementation + " round=" + round + " success=" + row.get("success") + " ms=" + row.get("milliseconds"));
                if (!Boolean.TRUE.equals(row.get("success"))) {
                    throw new IllegalStateException("Benchmark stopped after failed request; partial result saved");
                }
                Thread.sleep(pauseMillis);
            }
        }
    }
}
