package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 전용 루프백 PostgreSQL 18 DB에서만 실행한다. 스키마를 매번 만들고 테스트 종료 시 삭제한다.
// 10개 계정 × 20개 영상 × 재분석 3회 × 장소 5개. 50 VU, 요청 후 1초 대기.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:45487/trova_places_local",
        "spring.datasource.username=places_bench",
        "spring.datasource.password=${PLACES_BENCH_LOCAL_PASSWORD}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIfEnvironmentVariable(named = "TROVA_CONCURRENT_PLACES_BENCH", matches = "1")
class PlaceListConcurrentHttpTest {
    @LocalServerPort private int port;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;

    @Test
    void 로컬_장소_목록의_동시_요청을_측정한다() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int account = 0; account < 10; account++) {
                int index = account;
                User user = transactions.execute(status -> seed(index));
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/places"))
                        .header("Authorization", "Bearer " + jwt.issue(user)).timeout(Duration.ofSeconds(30)).GET().build();
                requests.add(request);
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                bodies.add(response.body());
            }
            run(client, requests, bodies, 30);
            run(client, requests, bodies, 120);
        }
    }

    private User seed(int account) {
        User user = new User("google", "local-load-" + account, "Synthetic", null);
        em.persist(user);
        for (int revision = 0; revision < 3; revision++) {
            for (int video = 0; video < 20; video++) {
                ProcessingJob job = new ProcessingJob(user, "https://youtu.be/bench" + video, SourcePlatform.YOUTUBE);
                em.persist(job);
                for (int place = 0; place < 5; place++) {
                    SavedPlace item = new SavedPlace(job, user, "Place " + account + "-" + revision + "-" + place,
                            "Seoul", "cafe", 37.5, 127.0);
                    item.applyVideoNotes(List.of("first", "second"));
                    em.persist(item);
                }
            }
        }
        em.createQuery("update SavedPlace p set p.createdAt = :at where p.user.id = :id")
                .setParameter("at", LocalDateTime.of(2026, 1, 1, 0, 0)).setParameter("id", user.getId()).executeUpdate();
        return user;
    }

    private void run(HttpClient client, List<HttpRequest> requests, List<byte[]> bodies, int seconds) throws Exception {
        List<Long> samples = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Long>>> workers = new ArrayList<>();
            CompletableFuture<Long> deadline = new CompletableFuture<>();
            for (int vu = 0; vu < 50; vu++) {
                int account = vu % requests.size();
                workers.add(pool.submit(() -> {
                    List<Long> elapsed = new ArrayList<>();
                    long end = deadline.join();
                    while (System.nanoTime() < end) {
                        long begin = System.nanoTime();
                        HttpResponse<byte[]> response = client.send(requests.get(account), HttpResponse.BodyHandlers.ofByteArray());
                        elapsed.add(System.nanoTime() - begin);
                        if (response.statusCode() != 200 || !Arrays.equals(bodies.get(account), response.body())) {
                            throw new AssertionError("Unexpected response for synthetic account " + account);
                        }
                        Thread.sleep(1000);
                    }
                    return elapsed;
                }));
            }
            long begin = System.nanoTime();
            deadline.complete(begin + TimeUnit.SECONDS.toNanos(seconds));
            for (Future<List<Long>> worker : workers) samples.addAll(worker.get());
            double duration = (System.nanoTime() - begin) / 1e9;
            Collections.sort(samples);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            bodies.forEach(digest::update);
            System.out.printf(Locale.ROOT, "PLACES_LOAD seconds=%d count=%d p50_ms=%.3f p95_ms=%.3f rps=%.3f sha256=%s%n",
                    seconds, samples.size(), percentile(samples, .50), percentile(samples, .95), samples.size() / duration,
                    HexFormat.of().formatHex(digest.digest()));
        }
    }

    private double percentile(List<Long> samples, double fraction) {
        return samples.get((int) Math.ceil(samples.size() * fraction) - 1) / 1e6;
    }
}
