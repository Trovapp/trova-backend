package com.trova.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.*;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.push.ExpoPushService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripEventsWireTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired JwtService jwt;
    @MockitoBean ExpoPushService expo;

    @Test void committedEditAndDeletionReachReadyEditorBeforeStreamEnds() throws Exception {
        User owner = user(), editor = user(), offline = user();
        var trip = new Trip(owner, "여행", LocalDate.now(), LocalDate.now());
        trip.getMembers().add(new TripMember(editor, TripMember.Role.EDITOR));
        trip.getMembers().add(new TripMember(offline, TripMember.Role.EDITOR));
        trips.saveAndFlush(trip);
        String url = "http://127.0.0.1:" + port + "/api/trips/" + trip.getId();
        var open = HttpRequest.newBuilder(URI.create(url + "/events"))
                .header("Authorization", "Bearer " + jwt.issue(editor)).timeout(Duration.ofSeconds(10)).build();
        var edit = HttpRequest.newBuilder(URI.create(url + "/title"))
                .header("Authorization", "Bearer " + jwt.issue(owner)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"title\":\"새 여행\"}")).build();

        try (var client = HttpClient.newHttpClient(); var readerThread = Executors.newVirtualThreadPerTaskExecutor()) {
            var opened = client.send(open, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = opened.body()) {
                assertThat(opened.statusCode()).isEqualTo(200);
                var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                String ready = readerThread.submit(() -> readFrame(reader)).get(10, TimeUnit.SECONDS);
                assertThat(ready).startsWith("event:ready\n").contains("\"version\"");
                var changed = readerThread.submit(() -> readFrame(reader));
                var response = client.send(edit, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                String frame = changed.get(3, TimeUnit.SECONDS);
                assertThat(frame).startsWith("event:changed\ndata:");
                var payload = new ObjectMapper().readTree(frame.substring(frame.indexOf("data:") + 5));
                assertThat(payload.path("type").asText()).isEqualTo("TRIP_TITLE");
                assertThat(payload.path("tripId").asLong()).isEqualTo(trip.getId());
                assertThat(payload.path("actorId").asLong()).isEqualTo(owner.getId());

                verify(expo, timeout(3000)).sendToUser(eq(offline.getId()), eq("trip-change"), any());
                var denied = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + jwt.issue(editor))
                        .DELETE().build();
                assertThat(client.send(denied, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);

                var delete = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + jwt.issue(owner))
                        .DELETE().build();
                var terminal = readerThread.submit(() -> readFrame(reader));
                assertThat(client.send(delete, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
                String last = terminal.get(3, TimeUnit.SECONDS);
                assertThat(last).startsWith("event:changed\ndata:");
                var deletion = new ObjectMapper().readTree(last.substring(last.indexOf("data:") + 5));
                assertThat(deletion.path("type").asText()).isEqualTo("TRIP_DELETED");
                assertThat(deletion.path("version").asLong()).isEqualTo(payload.path("version").asLong() + 1);
                assertThat(deletion.path("actorId").asLong()).isEqualTo(owner.getId());
                assertThat(deletion.size()).isEqualTo(6);
                assertThat(readerThread.submit(() -> reader.readLine()).get(3, TimeUnit.SECONDS)).isNull();
                for (var member : java.util.List.of(editor, offline)) {
                    verify(expo, timeout(3000)).sendToUser(eq(member.getId()), eq("trip-deleted"),
                            argThat(message -> message.title().equals("친구가 여행을 삭제했어요")));
                }
                verify(expo, never()).sendToUser(eq(owner.getId()), anyString(), any());
            }
        }
    }

    private User user() {
        return users.save(new User("google", UUID.randomUUID().toString(), "친구", null));
    }

    // A blank line dispatches an SSE event. Heartbeat comments carry no event.
    private String readFrame(BufferedReader reader) throws IOException {
        var frame = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() && !frame.isEmpty()) return frame.toString();
            if (!line.isEmpty() && !line.startsWith(":")) frame.append(line).append('\n');
        }
        throw new EOFException("SSE closed before the frame terminator: " + frame);
    }
}
