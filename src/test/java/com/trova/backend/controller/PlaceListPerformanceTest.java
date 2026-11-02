package com.trova.backend.controller;

import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.User;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.SavedPlaceListRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, synthetic in-memory data only. Timing is evidence, never a pass/fail threshold. */
@DataJpaTest(showSql = false)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@EnabledIfEnvironmentVariable(named = "TROVA_PLACE_LIST_BENCH", matches = "1")
class PlaceListPerformanceTest {
    @Autowired private SavedPlaceRepository repository;
    @Autowired private EntityManager em;
    @Autowired private EntityManagerFactory emf;
    @Autowired private javax.sql.DataSource dataSource;

    private final com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void 합성_목록의_조회와_응답_변환_비용을_기록한다() throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        List<String> csv = new ArrayList<>();
        csv.add("places,iteration,variant,query_ns,mapping_ns,total_ns,allocated_bytes,sql_count,entities_loaded,returned");
        for (int size : List.of(100, 1000)) {
            User user = seed(size);
            for (int i = 0; i < 20; i++) {
                measure(user, size, -1, false);
                measure(user, size, -1, true);
            }
            for (int i = 0; i < 50; i++) {
                csv.add(measure(user, size, i, i % 2 == 0));
                csv.add(measure(user, size, i, i % 2 != 0));
            }
        }
        Path output = Path.of("build/reports/place-list-comparison.csv");
        Files.createDirectories(output.getParent());
        Files.write(output, csv);
    }

    private User seed(int count) {
        User user = new User("google", "bench-" + count, "Synthetic", null);
        em.persist(user);
        for (int j = 0; j < count / 10; j++) {
            ProcessingJob job = new ProcessingJob(user, "https://youtu.be/bench" + j + "?si=synthetic", SourcePlatform.YOUTUBE);
            job.setTitle("Synthetic video " + j);
            em.persist(job);
            for (int i = 0; i < 10; i++) {
                SavedPlace place = new SavedPlace(job, user, "Place " + i, "Seoul", "cafe", 37.5, 127.0);
                place.applyVideoNotes(List.of("Synthetic note one", "Synthetic note two"));
                place.applyOpeningHours("synthetic", "{\"periods\":\"" + "x".repeat(512) + "\"}");
                em.persist(place);
            }
        }
        em.flush();
        em.clear();
        return user;
    }

    private String measure(User user, int size, int iteration, boolean projection) {
        em.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        long queried;
        List<PlacesController.PlaceResponse> response;
        if (projection) {
            List<SavedPlaceListRow> rows = repository.findListRowsByUserId(user.getId());
            queried = System.nanoTime();
            response = VideoResults.latestRows(rows).stream().map(PlacesController.PlaceResponse::from).toList();
        } else {
            List<SavedPlace> places = repository.findByUserOrderByCreatedAtDescIdDesc(user);
            queried = System.nanoTime();
            Map<String, Long> latest = VideoResults.latestJobIdByVideo(places);
            response = places.stream()
                    .filter(p -> p.getProcessingJob().getId().equals(latest.get(ShareUrl.videoKey(p.getProcessingJob().getSourceUrl()))))
                    .map(PlacesController.PlaceResponse::from).toList();
        }
        long end = System.nanoTime();
        long bytes = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        assertThat(response).hasSize(size);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        if (projection) assertThat(stats.getEntityLoadCount()).isZero();
        return String.join(",", Integer.toString(size), Integer.toString(iteration), projection ? "projection" : "entity",
                Long.toString(queried-start), Long.toString(end-queried), Long.toString(end-start),
                Long.toString(bytes), Long.toString(stats.getPrepareStatementCount()),
                Long.toString(stats.getEntityLoadCount()), Integer.toString(response.size()));
    }
}
