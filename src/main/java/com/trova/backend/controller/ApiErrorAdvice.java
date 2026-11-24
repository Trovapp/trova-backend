package com.trova.backend.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;


@RestControllerAdvice(basePackages = "com.trova.backend")
class ApiErrorAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectMapper json;

    ApiErrorAdvice(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        int status = ((ServletServerHttpResponse) response).getServletResponse().getStatus();
        if (status < 400) return body;
        String traceId = ApiErrors.logError(status);
        response.getHeaders().set(ApiErrors.TRACE_HEADER, traceId);
        // 문자열·바이트 변환기는 ObjectNode를 쓸 수 없고, JSON 배열도 기존 모양을 보존해야 한다.
        if (!StringHttpMessageConverter.class.isAssignableFrom(converter)
                && !ByteArrayHttpMessageConverter.class.isAssignableFrom(converter)) {
            JsonNode tree = body == null ? json.createObjectNode() : json.valueToTree(body);
            if (tree instanceof ObjectNode fields) return ApiErrors.body(status, fields, traceId);
        }
        return body;
    }
}
