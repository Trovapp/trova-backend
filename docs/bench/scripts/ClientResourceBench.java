package com.trova.backend.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.config.SpringAiConfig;
import io.micrometer.observation.ObservationRegistry;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * REST 직접 호출과 Spring AI 2 ChatModel을 같은 모델·프롬프트·20초 제한으로 번갈아 부르며
 * 응답 시간뿐 아니라 호출당 CPU 시간·할당 메모리·GC 횟수를 함께 잰다(#422).
 * ClientLatencyBench(#288)와 같은 방식이고, 실패해도 멈추지 않고 기록한 뒤 계속한다.
 * 서버·DB·대기열은 실행하지 않는다. 앱·라이브러리 재시도는 둘 다 꺼져 있다(SpringAiConfig 그대로).
 * 인자: 출력 JSON 경로, 클라이언트별 표본 수(1..40), 호출 사이 대기 ms(>=6000), 프롬프트 종류(short|long).
 */
public class ClientResourceBench {
    public static void main(String[] args) throws Exception {
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int pauseMillis = args.length > 2 ? Integer.parseInt(args[2]) : 6_000;
        String promptKind = args.length > 3 ? args[3] : "short";
        if (samples < 1 || samples > 40 || pauseMillis < 6_000) {
            throw new IllegalArgumentException("samples must be 1..40; pause must be >= 6000ms");
        }
        String key = System.getenv("TROVA_BENCH_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("TROVA_BENCH_KEY required");
        String text = prompt(promptKind);
        ObjectMapper mapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(20));
        RestClient rest = RestClient.builder().requestFactory(factory)
                .baseUrl("https://generativelanguage.googleapis.com").build();
        SpringAiConfig config = new SpringAiConfig();
        com.google.genai.Client client = config.geminiClient(key);
        GoogleGenAiChatModel model = config.geminiChatModel(client,
                new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(SpringAiConfig.MODEL)
                .temperature(0.1).responseMimeType("application/json").build();

        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long tid = Thread.currentThread().threadId();

        List<Map<String, Object>> rows = new ArrayList<>();
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        for (int round = 0; round <= samples; round++) {
            List<String> order = round % 2 == 0 ? List.of("rest", "spring-ai") : List.of("spring-ai", "rest");
            for (String implementation : order) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("client", implementation);
                row.put("warmup", round == 0);
                row.put("round", round);
                long cpu0 = os.getProcessCpuTime();
                long alloc0 = threads.getThreadAllocatedBytes(tid);
                long gcCount0 = 0, gcTime0 = 0;
                for (GarbageCollectorMXBean gc : gcs) { gcCount0 += gc.getCollectionCount(); gcTime0 += gc.getCollectionTime(); }
                long start = System.nanoTime();
                try {
                    String response;
                    if (implementation.equals("rest")) {
                        String raw = rest.post().uri("/v1beta/models/{model}:generateContent", SpringAiConfig.MODEL)
                                .header("x-goog-api-key", key)
                                .body(Map.of("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", text)))),
                                        "generationConfig", Map.of("temperature", 0.1, "responseMimeType", "application/json")))
                                .retrieve().body(String.class);
                        JsonNode parsed = mapper.readTree(raw);
                        response = parsed.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
                        row.put("promptTokens", parsed.path("usageMetadata").path("promptTokenCount").asInt());
                        row.put("tokens", parsed.path("usageMetadata").path("totalTokenCount").asInt());
                    } else {
                        ChatResponse result = model.call(new Prompt(text, options));
                        response = result.getResult().getOutput().getText();
                        row.put("promptTokens", result.getMetadata().getUsage().getPromptTokens());
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
                        if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.io.InterruptedIOException) row.put("timeout", true);
                    }
                }
                long elapsedNanos = System.nanoTime() - start;
                long gcCount1 = 0, gcTime1 = 0;
                for (GarbageCollectorMXBean gc : gcs) { gcCount1 += gc.getCollectionCount(); gcTime1 += gc.getCollectionTime(); }
                row.put("milliseconds", elapsedNanos / 1_000_000.0);
                // 프로세스 전체 CPU 시간 차이 — 호출 사이에는 이 스레드만 일하므로 그 호출의 몫으로 본다.
                row.put("cpuMillis", (os.getProcessCpuTime() - cpu0) / 1_000_000.0);
                row.put("allocatedKiB", (threads.getThreadAllocatedBytes(tid) - alloc0) / 1024.0);
                row.put("gcCount", gcCount1 - gcCount0);
                row.put("gcMillis", gcTime1 - gcTime0);
                rows.add(row);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("environment", "local Mac; no server/DB/queue; sequential alternating order");
                summary.put("model", SpringAiConfig.MODEL);
                summary.put("promptKind", promptKind);
                summary.put("promptChars", text.length());
                summary.put("scope", "client invocations; application/library retries disabled; round 0 excluded as warmup; failures recorded and run continues");
                summary.put("timeoutMillis", 20_000); // SpringAiConfig.READ_TIMEOUT_MS(패키지 전용)와 같은 값, REST 읽기 제한도 20초
                summary.put("samplesPerClient", samples);
                summary.put("pauseMillis", pauseMillis);
                summary.put("wireAttemptsObserved", false);
                summary.put("jvm", System.getProperty("java.version") + " " + System.getProperty("os.arch"));
                summary.put("rssKiBAtEnd", rssKiB());
                summary.put("results", rows);
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), summary);
                System.out.println(implementation + " round=" + round + " success=" + row.get("success") + " ms=" + row.get("milliseconds")
                        + " cpu=" + row.get("cpuMillis") + " allocKiB=" + row.get("allocatedKiB") + " gc=" + row.get("gcCount"));
                Thread.sleep(pauseMillis);
            }
        }
    }

    static String prompt(String kind) {
        String shortText = "다음 JSON 객체만 반환하세요: {\"ok\":true}";
        if (kind.equals("short")) return shortText;
        // 일정 초안 요청과 비슷한 크기(약 700~900 토큰)의 입력. 장소 목록을 읽게 하되 답은 작게 고정한다.
        StringBuilder sb = new StringBuilder("아래는 어떤 여행 영상에서 뽑은 장소 목록입니다. 목록을 모두 읽은 뒤, 설명은 쓰지 말고 다음 JSON 객체만 반환하세요: {\"ok\":true,\"count\":<장소 수>}\n\n");
        String[] names = {"광안리 해변", "해운대 블루라인파크", "감천문화마을", "자갈치시장", "태종대", "흰여울문화마을", "전포 카페거리", "부산 시민공원",
                "송정 해수욕장", "용두산공원", "국제시장", "오륙도 스카이워크", "다대포 해수욕장", "범어사", "영도 흰여울길", "서면 먹자골목",
                "센텀시티", "동백섬", "기장 죽성성당", "해동 용궁사", "온천장", "부산역", "남포동", "보수동 책방골목"};
        for (int i = 0; i < names.length; i++) {
            sb.append(i + 1).append(". ").append(names[i])
              .append(" — 영상에서 약 ").append(10 + i * 7).append("초에 등장. 화자는 이곳을 '꼭 가볼 곳'이라고 말했고, 주변에 카페와 식당이 많다고 설명했다. ")
              .append("추천 방문 시간대는 ").append(i % 2 == 0 ? "오전" : "저녁").append("이고 머무는 시간은 약 ").append(40 + (i % 5) * 20).append("분이다.\n");
        }
        return sb.toString();
    }

    static long rssKiB() {
        try {
            if (Files.exists(Path.of("/proc/self/status"))) {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(ProcessHandle.current().pid())).start();
            return Long.parseLong(new String(ps.getInputStream().readAllBytes()).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
