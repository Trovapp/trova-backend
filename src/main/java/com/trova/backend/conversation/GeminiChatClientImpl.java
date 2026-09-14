package com.trova.backend.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GeminiChatClientImpl implements GeminiChatClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClientImpl.class);
    // GeminiTextClientImpl과 동일 모델 — 이 프로젝트가 실제로 쓰는 생성 모델과 통일한다.
    private static final String MODEL = "gemini-3.5-flash-lite";

    private final String apiKey;
    private final RestClient restClient;

    public GeminiChatClientImpl(
            @Value("${app.pipeline.gemini-api-key}") String apiKey,
            RestClient.Builder restClientBuilder
    ) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    // 2026-09-14 실제 호출로 검증한 형태를 그대로 모델링한다 — 위 "사전 조사로 확인된
    // 사실" 절 참고. Part는 text/functionCall/functionResponse/thoughtSignature 중
    // 실제로 쓰는 필드만 담고 나머지는 JsonInclude.NON_NULL로 생략한다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ReqPart(String text, ReqFunctionCall functionCall, ReqFunctionResponse functionResponse, String thoughtSignature) {
        static ReqPart ofText(String text) {
            return new ReqPart(text, null, null, null);
        }
        static ReqPart ofFunctionCall(String name, Map<String, Object> args, String thoughtSignature) {
            return new ReqPart(null, new ReqFunctionCall(name, args), null, thoughtSignature);
        }
        static ReqPart ofFunctionResponse(String name, Map<String, Object> response) {
            return new ReqPart(null, null, new ReqFunctionResponse(name, response), null);
        }
    }
    private record ReqFunctionCall(String name, Map<String, Object> args) {
    }
    private record ReqFunctionResponse(String name, Map<String, Object> response) {
    }
    private record ReqContent(String role, List<ReqPart> parts) {
    }
    private record ReqTool(List<ReqFunctionDeclaration> functionDeclarations) {
    }
    private record ReqFunctionDeclaration(String name, String description, ReqParameters parameters) {
    }
    private record ReqParameters(String type, Map<String, ReqPropertySchema> properties) {
    }
    private record ReqPropertySchema(String type, String description) {
    }
    private record ChatRequest(List<ReqContent> contents, List<ReqTool> tools) {
    }

    private record RespFunctionCall(String name, Map<String, Object> args) {
    }
    private record RespPart(String text, RespFunctionCall functionCall, String thoughtSignature) {
    }
    private record RespContent(List<RespPart> parts) {
    }
    private record RespCandidate(RespContent content) {
    }
    private record ChatResponse(List<RespCandidate> candidates) {
    }

    @Override
    public ChatResult sendMessage(List<HistoryTurn> history, String userMessage, List<ToolDeclaration> tools) {
        List<ReqContent> contents = buildHistoryContents(history);
        contents.add(new ReqContent("user", List.of(ReqPart.ofText(userMessage))));
        return call(contents, tools);
    }

    @Override
    public ChatResult sendFunctionResult(
            List<HistoryTurn> history, String userMessage, FunctionCall functionCall,
            Map<String, Object> functionResult, List<ToolDeclaration> tools
    ) {
        List<ReqContent> contents = buildHistoryContents(history);
        contents.add(new ReqContent("user", List.of(ReqPart.ofText(userMessage))));
        contents.add(new ReqContent("model", List.of(
                ReqPart.ofFunctionCall(functionCall.name(), functionCall.args(), functionCall.thoughtSignature()))));
        contents.add(new ReqContent("user", List.of(
                ReqPart.ofFunctionResponse(functionCall.name(), functionResult))));
        return call(contents, tools);
    }

    private List<ReqContent> buildHistoryContents(List<HistoryTurn> history) {
        List<ReqContent> contents = new ArrayList<>();
        for (HistoryTurn turn : history) {
            contents.add(new ReqContent(turn.role(), List.of(ReqPart.ofText(turn.text()))));
        }
        return contents;
    }

    private ChatResult call(List<ReqContent> contents, List<ToolDeclaration> tools) {
        try {
            List<ReqFunctionDeclaration> declarations = tools.stream()
                    .map(t -> new ReqFunctionDeclaration(
                            t.name(), t.description(),
                            new ReqParameters("object", t.parameters().entrySet().stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey,
                                            e -> new ReqPropertySchema(e.getValue().type(), e.getValue().description()))))))
                    .toList();
            ChatRequest request = new ChatRequest(contents, List.of(new ReqTool(declarations)));

            ChatResponse response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", MODEL)
                    .header("x-goog-api-key", apiKey)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);

            if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
                return new ChatResult(null, null);
            }
            List<RespPart> parts = response.candidates().get(0).content().parts();
            if (parts == null || parts.isEmpty()) {
                return new ChatResult(null, null);
            }
            // 응답의 parts 리스트에는 functionCall 앞에 짧은 텍스트("thinking" 텍스트
            // 등)가 먼저 올 수 있다 — 첫 번째 part만 보면 그 뒤에 오는 functionCall을
            // 놓치고 텍스트만 반환하게 된다(도구가 조용히 호출되지 않는 버그). 전체
            // parts를 훑어서 functionCall을 우선하고, 없으면 텍스트 part들을 순서대로
            // 이어붙인다.
            StringBuilder textBuilder = new StringBuilder();
            for (RespPart part : parts) {
                if (part.functionCall() != null) {
                    return new ChatResult(
                            new FunctionCall(part.functionCall().name(), part.functionCall().args(), part.thoughtSignature()),
                            null);
                }
                if (part.text() != null) {
                    textBuilder.append(part.text());
                }
            }
            return new ChatResult(null, textBuilder.isEmpty() ? null : textBuilder.toString());
        } catch (Exception e) {
            log.warn("Gemini 대화 호출 실패", e);
            return new ChatResult(null, null);
        }
    }
}
