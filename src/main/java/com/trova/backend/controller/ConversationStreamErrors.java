package com.trova.backend.controller;

import org.springframework.http.HttpStatus;
import org.slf4j.MDC;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** 스트림이 시작된 뒤에도 일반 API와 같은 오류 필드·메시지·추적 ID를 사용한다. */
public final class ConversationStreamErrors {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ConversationStreamErrors() {}

    public static ObjectNode body(Exception failure, String path, String traceId) {
        int status = failure instanceof ResponseStatusException response ? response.getStatusCode().value() : 500;
        HttpStatus known = HttpStatus.resolve(status);
        ObjectNode fields = JSON.createObjectNode();
        fields.put("timestamp", Instant.now().toString());
        fields.put("path", path);
        fields.put("error", known == null ? "Error" : known.getReasonPhrase());
        String previous = MDC.get("traceId");
        try {
            MDC.put("traceId", traceId);
            ApiErrors.logError(status);
        } finally {
            if (previous == null) MDC.remove("traceId");
            else MDC.put("traceId", previous);
        }
        return ApiErrors.body(status, fields, traceId);
    }
}
