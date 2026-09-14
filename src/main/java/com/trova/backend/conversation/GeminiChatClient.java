package com.trova.backend.conversation;

import java.util.List;
import java.util.Map;

public interface GeminiChatClient {

    ChatResult sendMessage(List<HistoryTurn> history, String userMessage, List<ToolDeclaration> tools);

    ChatResult sendFunctionResult(
            List<HistoryTurn> history, String userMessage, FunctionCall functionCall,
            Map<String, Object> functionResult, List<ToolDeclaration> tools);

    record HistoryTurn(String role, String text) {
    }

    record ToolDeclaration(String name, String description, Map<String, ParamSchema> parameters) {
    }

    record ParamSchema(String type, String description) {
    }

    record FunctionCall(String name, Map<String, Object> args, String thoughtSignature) {
    }

    /** functionCall과 text 중 정확히 하나만 채워진다. 호출 자체가 실패하면 둘 다 null이다. */
    record ChatResult(FunctionCall functionCall, String text) {
    }
}
