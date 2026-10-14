package com.trova.backend.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Expo 푸시 API(https://exp.host/--/api/v2/push/send) — 무료, 접근 토큰 없이(ADR 0017). Java 기본 HttpClient. */
@Component
public class HttpExpoPushClient implements ExpoPushClient {

    private static final URI SEND = URI.create("https://exp.host/--/api/v2/push/send");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Response send(List<Map<String, Object>> messages) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(SEND)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(messages)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return new Response(response.statusCode(), List.of());
        }
        List<String> errors = new ArrayList<>();
        for (JsonNode ticket : MAPPER.readTree(response.body()).path("data")) {
            if ("error".equals(ticket.path("status").asText())) {
                String code = ticket.path("details").path("error").asText("");
                errors.add(code.isEmpty() ? ticket.path("message").asText("error") : code);
            } else {
                errors.add(null);
            }
        }
        return new Response(200, errors);
    }
}
