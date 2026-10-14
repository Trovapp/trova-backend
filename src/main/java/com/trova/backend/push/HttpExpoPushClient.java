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
        return new Response(200, parseTickets(response.body()));
    }

    /**
     * 티켓 배열을 보낸 순서대로 오류 코드(성공은 null)로. data가 배열이 아니면(형식이 다르거나 요청 전체 오류) 빈 목록 —
     * 서비스가 모자란 티켓을 실패로 센다. 하나짜리 객체의 칸들을 티켓처럼 세어 성공으로 잘못 세지 않게(#200).
     */
    static List<String> parseTickets(String body) {
        List<String> errors = new ArrayList<>();
        JsonNode data;
        try {
            data = MAPPER.readTree(body).path("data");
        } catch (Exception e) {
            return errors;
        }
        if (!data.isArray()) {
            return errors;
        }
        for (JsonNode ticket : data) {
            if ("error".equals(ticket.path("status").asText())) {
                String code = ticket.path("details").path("error").asText("");
                errors.add(code.isEmpty() ? ticket.path("message").asText("error") : code);
            } else {
                errors.add(null);
            }
        }
        return errors;
    }
}
