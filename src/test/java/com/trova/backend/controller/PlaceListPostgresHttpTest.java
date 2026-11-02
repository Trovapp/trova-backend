package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.CurrentUserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed loopback-only disposable database; legacy endpoint exists in tests only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/trova_bench",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.username=trova_bench",
        "spring.datasource.password=local-disposable-bench",
        "spring.datasource.hikari.data-source-properties.prepareThreshold=0",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.queue.type=async"
})
@Import(PlaceListPostgresHttpTest.LegacyEndpoint.class)
@EnabledIfEnvironmentVariable(named = "TROVA_POSTGRES_HTTP_BENCH", matches = "1")
class PlaceListPostgresHttpTest {
    @LocalServerPort private int port;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JwtService jwt;
    @Autowired private javax.sql.DataSource dataSource;

    @RestController
    @TestComponent
    static class LegacyEndpoint {
        private final CurrentUserService users;
        private final SavedPlaceRepository places;

        LegacyEndpoint(CurrentUserService users, SavedPlaceRepository places) {
            this.users = users;
            this.places = places;
        }

        @GetMapping("/api/bench/places-legacy")
        List<PlacesController.PlaceResponse> list(Authentication authentication) {
            User user = users.resolve(authentication);
            List<SavedPlace> rows = places.findByUserOrderByCreatedAtDescIdDesc(user);
            Map<String, Long> latest = VideoResults.latestJobIdByVideo(rows);
            return rows.stream()
                    .filter(p -> p.getProcessingJob().getId().equals(latest.get(ShareUrl.videoKey(p.getProcessingJob().getSourceUrl()))))
                    .map(PlacesController.PlaceResponse::from).toList();
        }
    }

    @Test
    void 격리_PostgreSQL에서_동일한_JSON의_HTTP_응답시간을_교대로_비교한다() throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo("jdbc:postgresql://127.0.0.1:15432/trova_bench");
        }
        List<String> csv = new ArrayList<>();
        csv.add("places,iteration,variant,http_ns,status,response_bytes");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            for (int size : List.of(100, 1000)) {
                User user = transactions.execute(status -> seed(size));
                String token = jwt.issue(user);
                String expected = request(client, token, false).body();
                for (int i = 0; i < 20; i++) {
                    assertThat(request(client, token, false).body()).isEqualTo(expected);
                    assertThat(request(client, token, true).body()).isEqualTo(expected);
                }
                for (int i = 0; i < 50; i++) {
                    measure(client, token, size, i, i % 2 == 0, expected, csv);
                    measure(client, token, size, i, i % 2 != 0, expected, csv);
                }
            }
        }
        Path output = Path.of("build/reports/place-list-postgres-http.csv");
        Files.createDirectories(output.getParent());
        Files.write(output, csv);
    }

    private User seed(int count) {
        User user = new User("google", "postgres-http-" + count, "Synthetic", null);
        em.persist(user);
        for (int j = 0; j < count / 10; j++) {
            ProcessingJob job = new ProcessingJob(user, "https://youtu.be/bench" + j + "?si=synthetic", SourcePlatform.YOUTUBE);
            job.setTitle("Synthetic video " + j);
            job.markAnalysisDone();
            em.persist(job);
            for (int i = 0; i < 10; i++) {
                SavedPlace place = new SavedPlace(job, user, "Place " + i, "Seoul", "cafe", 37.5, 127.0);
                place.applyVideoNotes(List.of("Synthetic note one", "Synthetic note two"));
                place.applyOpeningHours("synthetic", "{\"periods\":\"" + "x".repeat(512) + "\"}");
                em.persist(place);
            }
        }
        return user;
    }

    private HttpResponse<String> request(HttpClient client, String token, boolean improved) throws Exception {
        String path = improved ? "/api/places" : "/api/bench/places-legacy";
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private void measure(HttpClient client, String token, int size, int iteration, boolean improved,
                         String expected, List<String> csv) throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = request(client, token, improved);
        long nanos = System.nanoTime() - start;
        assertThat(response.body()).isEqualTo(expected);
        csv.add(size + "," + iteration + "," + (improved ? "projection" : "entity") + "," + nanos + ","
                + response.statusCode() + "," + response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}
