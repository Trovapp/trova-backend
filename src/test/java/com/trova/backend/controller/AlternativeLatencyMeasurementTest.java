package com.trova.backend.controller;

import com.trova.backend.congestion.SeoulCongestionApiClient;
import com.trova.backend.embedding.GeminiEmbeddingClient;
import com.trova.backend.embedding.GeminiTextClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.pipeline.PlaceTaggingRunner;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P8 분석 전용: 실제 외부 호출 없이 기존 요청 경로와 SQL 증가율을 측정한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p8-alternatives;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "logging.level.org.hibernate.orm.jdbc.error=OFF",
        "logging.level.com.trova.backend.recommendation.PersonalizationService=ERROR",
        "logging.level.com.trova.backend.recommendation.PlaceEmbeddingService=ERROR",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureMockMvc
class AlternativeLatencyMeasurementTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private TripPlaceRepository tripPlaces;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;
    @MockitoBean private GooglePlacesApiClient google;
    @MockitoBean private GeminiEmbeddingClient embedding;
    @MockitoBean private GeminiTextClient text;
    @MockitoBean private SeoulCongestionApiClient congestion;
    @MockitoBean private PlaceTaggingRunner tagging;

    private double sqlTotal() {
        DistributionSummary summary = meters.find("trova.db.queries.per.request")
                .tags("uri", "/api/trip-places/{id}/alternatives", "method", "GET").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private void request(String token, Long id, int count) throws Exception {
        String body = mvc.perform(get("/api/trip-places/" + id + "/alternatives")
                        .param("category", "cafe").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)).hasSize(count);
    }

    @Test
    void 외부를_스텁하고_후보별_SQL과_내부_시간을_측정한다() throws Exception {
        when(embedding.embedBatch(any())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return Optional.of(inputs.stream().map(input -> new float[] {1f, 0f}).toList());
        });
        for (String mode : List.of("missing-column", "stored-vector", "new-vector")) {
            boolean embedded = !mode.equals("missing-column");
            // varchar는 pgvector 대체가 아니다. 두 번째 native SQL의 제출까지 확인하는 용도다.
            if (mode.equals("stored-vector")) jdbc.execute("ALTER TABLE places ADD COLUMN embedding VARCHAR(100)");
            if (mode.equals("new-vector")) jdbc.execute("CREATE DOMAIN vector AS VARCHAR(100)");
            for (int count : new int[] {0, 1, 5, 20}) {
                User user = users.save(new User("google", "p8-" + UUID.randomUUID(), "측정", null));
                Trip trip = trips.save(new Trip(user, "측정 여행", null, null));
                Itinerary day = itineraries.save(new Itinerary(trip, 1, null));
                TripPlace target = tripPlaces.save(new TripPlace(day, "원본", "서울", "cafe",
                        37.5, 127.0, null, null, 1, PlaceSource.NORMAL, null));
                String prefix = UUID.randomUUID().toString();
                List<GooglePlacesNearbySearchResponse.Place> raw = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    raw.add(new GooglePlacesNearbySearchResponse.Place(prefix + i,
                            new GooglePlacesNearbySearchResponse.Place.DisplayName("후보" + i),
                            List.of("cafe"), 4.0, 10, null,
                            new GooglePlacesNearbySearchResponse.Place.Location(37.5, 127.0), "주소"));
                }
                when(google.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                        .thenReturn(new GooglePlacesNearbySearchResponse(raw));
                // 임베딩 있는 시나리오는 카탈로그만 먼저 준비한다(측정 밖).
                if (mode.equals("stored-vector")) {
                    for (GooglePlacesNearbySearchResponse.Place candidate : raw) {
                        jdbc.update("INSERT INTO places (google_place_id,name,category,rating,user_rating_count,latitude,longitude,embedding,last_synced_at) VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                                candidate.id(), candidate.displayName().text(), "cafe", 4.0, 10, 37.5, 127.0, "[1,0]");
                    }
                }
                String token = "Bearer " + jwt.issue(user);
                clearInvocations(embedding);
                double beforeCold = sqlTotal();
                long coldPreparedBefore = emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
                request(token, target.getId(), count);
                long coldSql = Math.round(sqlTotal() - beforeCold);
                long coldPrepared = emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount() - coldPreparedBefore;
                for (int i = 0; i < 10; i++) request(token, target.getId(), count);
                double[] times = new double[30];
                double before = sqlTotal();
                long preparedBefore = emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount();
                for (int i = 0; i < times.length; i++) {
                    long start = System.nanoTime();
                    request(token, target.getId(), count);
                    times[i] = (System.nanoTime() - start) / 1_000_000.0;
                }
                long prepared = emf.unwrap(SessionFactory.class).getStatistics().getPrepareStatementCount() - preparedBefore;
                Arrays.sort(times);
                double sql = (sqlTotal() - before) / times.length;
                System.out.printf("P8 mode=%s candidates=%d coldSql=%d coldHibernate=%d warmSql=%.1f hibernateSql=%.1f medianMs=%.3f minMs=%.3f maxMs=%.3f%n",
                        mode, count, coldSql, coldPrepared, sql, prepared / 30.0, (times[14] + times[15]) / 2, times[0], times[29]);
                assertThat(sql).isEqualTo(count == 0 ? 5 : 9);
                assertThat(prepared / 30.0).isEqualTo(sql);
                verify(embedding, times(mode.equals("new-vector") && count > 0 ? 1 : 0)).embedBatch(any());
                if (mode.equals("new-vector") && count > 0) {
                    assertThat(coldSql - coldPrepared).isEqualTo(2L * count);
                }
            }
        }
        verifyNoInteractions(text, congestion, tagging);
    }
}
