package com.trova.backend.controller;

import com.sun.management.OperatingSystemMXBean;
import com.trova.backend.entity.Bookmark;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.User;
import com.trova.backend.repository.BookmarkRepository;
import com.trova.backend.repository.PlaceRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.datasource.url=jdbc:h2:mem:listbench;MODE=PostgreSQL",
        "app.rate-limit.ip-per-minute=0"
})
@EnabledIfEnvironmentVariable(named = "TROVA_LIST_HTTP_BENCH", matches = "1")
class UserListHttpBenchmarkTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private BookmarkRepository bookmarks;
    @Autowired private SavedPlaceRepository saved;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private PlaceRepository places;
    @Autowired private JwtService jwt;

    @Test
    void 루프백_HTTP에서_세_목록의_지연과_처리량을_측정한다() throws Exception {
        List<List<HttpRequest>> accounts = new ArrayList<>();
        for (int account = 0; account < 20; account++) {
            User user = users.save(new User("google", "benchmark-" + account, "Synthetic", null));
            LocalDate day = LocalDate.of(2026, 1, 1);
            trips.save(new Trip(user, "Trip", day, day));
            ProcessingJob job = jobs.save(new ProcessingJob(user, "https://example.test/video", SourcePlatform.YOUTUBE));
            for (int row = 0; row < 5; row++) {
                saved.save(new SavedPlace(job, user, "Place " + row, "Region", "cafe", 1.0, 1.0));
                Place place = places.save(new Place("benchmark-" + account + "-" + row, "Bookmark", "cafe",
                        4.0, 1, null, 1.0, 1.0, "Region"));
                bookmarks.save(new Bookmark(user, place));
            }
            String bearer = "Bearer " + jwt.issue(user);
            accounts.add(List.of(request("places", bearer), request("trips", bearer), request("bookmarks", bearer)));
        }
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            sample(client, accounts, 1, 10);
            sample(client, accounts, 200, 60);
        }
    }

    private HttpRequest request(String path, String bearer) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/" + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", bearer).build();
    }

    private static void sample(HttpClient client, List<List<HttpRequest>> accounts, int vus, int seconds) throws Exception {
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        List<Callable<Void>> workers = new ArrayList<>();
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(seconds);
        long cpu = os.getProcessCpuTime();
        for (int vu = 0; vu < vus; vu++) {
            int index = vu;
            workers.add(() -> {
                List<HttpRequest> requests = accounts.get(index % accounts.size());
                int cycle = index;
                while (System.nanoTime() < deadline) {
                    int slot = cycle++ % 5;
                    HttpRequest request = requests.get(slot < 3 ? 0 : slot - 2);
                    long before = System.nanoTime();
                    try {
                        if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) {
                            errors.incrementAndGet();
                        }
                    } catch (java.io.IOException ex) {
                        errors.incrementAndGet();
                    }
                    times.add(System.nanoTime() - before);
                    Thread.sleep(1000);
                }
                return null;
            });
        }
        try (java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (java.util.concurrent.Future<Void> result : executor.invokeAll(workers)) result.get();
        }
        long elapsed = System.nanoTime() - start;
        long used = os.getProcessCpuTime() - cpu;
        Collections.sort(times);
        System.out.printf(Locale.ROOT,
                "LIST_BENCH vus=%d n=%d errors=%d p50_ms=%.2f p95_ms=%.2f rps=%.2f process_cpu_cores=%.3f%n",
                vus, times.size(), errors.get(), percentile(times, .5), percentile(times, .95),
                times.size() * 1e9 / elapsed, (double) used / elapsed);
        assertThat(times).isNotEmpty();
    }

    private static double percentile(List<Long> sorted, double fraction) {
        return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1) / 1e6;
    }
}
