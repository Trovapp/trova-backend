package com.trova.backend.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.*;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.*;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 실제 Tomcat·JWT·보안 헤더·flush 경계를 통과한다. Gemini/도구만 가짜다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationStreamHttpTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired ItineraryRepository itineraries;
    @Autowired TripPlaceRepository places;
    @Autowired JwtService jwt;
    @Autowired ConversationSessionStore sessions;
    @MockitoBean GeminiChatClient gemini;
    @MockitoBean ConversationToolExecutor tools;
    @MockitoBean PlaceEmbeddingService embeddings;
    private String token;
    private String session;
    private String firstMessage;

    @BeforeEach void setup() {
        User user = users.save(new User("google", UUID.randomUUID().toString(), "스트림", null));
        Trip trip = trips.save(new Trip(user, "여행", null, null));
        Itinerary day = itineraries.save(new Itinerary(trip, 1, null));
        TripPlace place = places.save(new TripPlace(day, "경복궁", "서울", "landmark", 37.58, 126.97,
                null, null, 1, PlaceSource.NORMAL, null));
        token = jwt.issue(user);
        session = UUID.randomUUID().toString();
        firstMessage = "{\"message\":\"찾아줘\",\"tripId\":%d,\"tripPlaceId\":%d}".formatted(trip.getId(), place.getId());
    }

    private HttpRequest request(String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/conversations/" + session + "/messages/stream"))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream")
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test void real_HTTP_flushes_cards_before_the_700ms_second_generation() throws Exception {
        var call = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        var candidate = new AlternativeCandidate(5L, "g5", "카페", "cafe", 4.5, 100,
                37.5, 127.0, "서울", null, null, false, null, null);
        when(gemini.sendMessage(any(), any(), any())).thenReturn(new GeminiChatClient.ChatResult(call, null));
        when(tools.execute(any(), any(), any(), any())).thenReturn(
                new ConversationToolExecutor.ToolExecutionResult(List.of(candidate), Map.of("candidates", List.of())));
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(700);
            return new GeminiChatClient.ChatResult(null, "찾았어요");
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            long start = System.nanoTime();
            var response = client.send(request(firstMessage), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).isEqualTo("event:cards");
                assertThat(reader.readLine()).contains("\"name\":\"카페\"");
                assertThat(reader.readLine()).isEmpty();
                double cardsMs = (System.nanoTime() - start) / 1_000_000.0;
                String remainder = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                double completeMs = (System.nanoTime() - start) / 1_000_000.0;
                assertThat(remainder).contains("event:reply", "찾았어요", "event:done");
                assertThat(remainder.indexOf("event:reply")).isLessThan(remainder.indexOf("event:done"));
                assertThat(completeMs - cardsMs).isGreaterThan(500);
                assertThat(sessions.get(session).getTurnCount()).isEqualTo(1);
                System.out.printf("T44 localhost HTTP 가짜 Gemini: cards=%.1fms, complete=%.1fms%n", cardsMs, completeMs);
            }
        }
    }

    @Test void pre_stream_errors_with_SSE_accept_keep_the_JSON_error_contract() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request("{\"message\":\"\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
            var body = new ObjectMapper().readTree(response.body());
            assertThat(body.get("code").asText()).isEqualTo("BAD_REQUEST");
            assertThat(body.get("status").asInt()).isEqualTo(400);
            assertThat(response.headers().firstValue("X-Trace-Id").orElseThrow()).isEqualTo(body.get("traceId").asText());
        }
    }

    @Test void post_stream_errors_keep_header_trace_and_normal_error_fields() throws Exception {
        when(gemini.sendMessage(any(), any(), any())).thenThrow(new IllegalStateException("private-failure"));
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request(firstMessage), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).startsWith("event:error\ndata:").doesNotContain("event:done", "private-failure");
            var body = new ObjectMapper().readTree(response.body().lines().filter(line -> line.startsWith("data:"))
                    .findFirst().orElseThrow().substring(5));
            assertThat(body.get("code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(body.get("status").asInt()).isEqualTo(500);
            assertThat(response.headers().firstValue("X-Trace-Id").orElseThrow()).isEqualTo(body.get("traceId").asText());
        }
    }
}
