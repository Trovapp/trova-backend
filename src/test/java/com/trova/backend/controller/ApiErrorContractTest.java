package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiErrorContractTest.FailureRoutes.class)
class ApiErrorContractTest {
    @LocalServerPort int port;

    @RestController
    @TestConfiguration(proxyBeanMethods = false)
    static class FailureRoutes {
        @GetMapping("/api/public/trips/test-empty")
        ResponseEntity<Void> empty(@RequestParam int status) {
            return ResponseEntity.status(status).header("Retry-After", "60").build();
        }

        @GetMapping("/api/public/trips/test-conflict")
        ResponseEntity<?> conflict() {
            return ResponseEntity.status(409).body(Map.of("code", "VERSION_CONFLICT", "latest", Map.of("id", 7)));
        }

        @GetMapping("/api/public/trips/test-failure")
        void failure() {
            throw new IllegalStateException("internal-only details");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "/api/public/trips/test-empty?status=abc,400,BAD_REQUEST",
            "/api/trips,401,UNAUTHORIZED",
            "/api/trips?cors,403,FORBIDDEN",
            "/api/trips/7/events?ticket=invalid,401,UNAUTHORIZED",
            "/api/public/trips/test-empty?status=403,403,FORBIDDEN",
            "/api/public/trips/test-empty?status=404,404,NOT_FOUND",
            "/api/public/trips/test-conflict,409,VERSION_CONFLICT",
            "/api/public/trips/test-empty?status=422,422,UNPROCESSABLE_CONTENT",
            "/api/public/trips/test-empty?status=429,429,TOO_MANY_REQUESTS",
            "/api/public/trips/test-failure,500,INTERNAL_SERVER_ERROR",
            "/api/public/trips/test-empty?status=503,503,SERVICE_UNAVAILABLE"
    })
    void 오류_경로별로_공통_필드와_기존_상세를_전달한다(String path, int status, String code) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", path.contains("/events") ? "text/event-stream" : "application/json");
        if (path.endsWith("?cors")) builder.header("Origin", "https://invalid.example");
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            JsonNode body = new ObjectMapper().readTree(response.body());
            assertThat(body.path("status").asInt()).isEqualTo(status);
            assertThat(body.path("code").asText()).isEqualTo(code);
            assertThat(body.path("message").asText()).containsPattern("[가-힣]");
            assertThat(body.path("traceId").asText()).isNotBlank();
            if (status == 409) assertThat(body.at("/latest/id").asInt()).isEqualTo(7);
            if (status == 429) assertThat(response.headers().firstValue("Retry-After")).contains("60");
            if (status == 500) {
                assertThat(body.has("timestamp")).isTrue();
                assertThat(body.path("path").asText()).isEqualTo(path);
                assertThat(body.path("error").asText()).isEqualTo("Internal Server Error");
                assertThat(response.body()).doesNotContain("internal-only", "IllegalStateException", "\"trace\"");
            }
        }
    }
}
