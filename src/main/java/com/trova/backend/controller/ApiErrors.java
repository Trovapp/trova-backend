package com.trova.backend.controller;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public final class ApiErrors {
    static final String TRACE_HEADER = "X-Trace-Id";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    private ApiErrors() {}

    static ObjectNode body(int status, ObjectNode fields, String traceId) {
        HttpStatus known = HttpStatus.resolve(status);
        if (!fields.has("code")) fields.put("code", known == null ? "HTTP_" + status : known.name());
        if (!fields.has("message")) fields.put("message", message(status, fields.get("code").asString()));
        fields.put("status", status);
        fields.put("traceId", traceId);
        return fields;
    }

    static String logError(int status) {
        String traceId = MDC.get("traceId");
        if (traceId == null) traceId = UUID.randomUUID().toString();
        if (status >= 500) LOG.warn("API 오류 status={} traceId={}", status, traceId);
        else LOG.info("API 오류 status={} traceId={}", status, traceId);
        return traceId;
    }

    private static String message(int status, String code) {
        return switch (code) {
            case "VERSION_CONFLICT" -> "여행 일정이 변경됐어요. 최신 내용을 확인해 주세요.";
            case "MEMBER_LIMIT" -> "이미 10명이 함께하고 있어요.";
            case "PLACE_MATCH_AMBIGUOUS" -> "지도에서 이 장소를 정확히 찾지 못했어요.";
            case "REAUTH_REQUIRED" -> "다시 인증해 주세요.";
            default -> switch (status) {
                case 400, 422 -> "입력한 내용을 확인해 주세요.";
                case 401 -> "로그인이 필요해요.";
                case 403 -> "이 요청을 수행할 권한이 없어요.";
                case 404 -> "요청한 대상을 찾을 수 없어요.";
                case 409 -> "내용이 변경됐어요. 다시 확인해 주세요.";
                case 429 -> "요청이 많아요. 잠시 후 다시 시도해 주세요.";
                default -> "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
            };
        };
    }

    public static void write(HttpServletResponse response, int status, Map<String, ?> details) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = logError(status);
        response.setHeader(TRACE_HEADER, traceId);
        response.getWriter().write(JSON.writeValueAsString(body(status, JSON.valueToTree(details), traceId)));
    }
}
