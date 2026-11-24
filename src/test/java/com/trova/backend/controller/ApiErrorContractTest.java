package com.trova.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.User;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiErrorContractTest.FailureRoutes.class)
class ApiErrorContractTest {
    @LocalServerPort int port;

    @Autowired JwtService jwt;
    @Autowired UserRepository users;

    @RestController
    @TestConfiguration(proxyBeanMethods = false)
    static class FailureRoutes {
        @GetMapping("/api/test-denied")
        void denied() {
            throw new AccessDeniedException("internal-denial");
        }

        @GetMapping("/api/public/trips/test-text")
        ResponseEntity<String> text() {
            return ResponseEntity.badRequest().body("legacy-text");
        }

        @GetMapping("/api/public/trips/test-list")
        ResponseEntity<List<String>> list() {
            return ResponseEntity.badRequest().body(List.of("legacy"));
        }

        @GetMapping("/api/public/trips/test-bytes")
        ResponseEntity<byte[]> bytes() {
            return ResponseEntity.badRequest().body(new byte[]{65, 66});
        }

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

    @Test
    void 일반_사용자의_접근_거부는_오류_디스패치에서_403으로_전달한다() throws Exception {
        User member = users.save(new User("google", UUID.randomUUID().toString(), "일반 사용자", null));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test-denied"))
                .header("Authorization", "Bearer " + jwt.issue(member)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            JsonNode body = new ObjectMapper().readTree(response.body());
            assertThat(body.path("path").asText()).isEqualTo("/api/test-denied");
            assertThat(body.path("error").asText()).isEqualTo("Forbidden");
            assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(body.path("traceId").asText()).isNotBlank();
            assertThat(response.body()).doesNotContain("internal-denial");
        }
    }

    @Test
    void OAuth_콜백이_실패하면_로그인_화면으로_리다이렉트한다() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/login/oauth2/code/google?error=access_denied&state=test")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).contains("http://localhost:3000/login?error=oauth_failed");
            assertThat(response.headers().firstValue("Content-Type").orElse("")).doesNotContain("json");
            assertThat(response.body()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"text,legacy-text", "list,[\"legacy\"]", "bytes,AB"})
    void 객체가_아닌_오류는_상태와_본문을_보존한다(String kind, String expected) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/public/trips/test-" + kind)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo(expected);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
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
