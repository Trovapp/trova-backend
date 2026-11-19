package com.trova.backend.controller;

import com.trova.backend.admin.action.DeleteTripAction;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청마다 독립 영속성 컨텍스트를 사용하도록 테스트 트랜잭션을 두지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.datasource.url=jdbc:h2:mem:trip-detail-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
class TripDetailQueryCountTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;
    @Autowired private DeleteTripAction deleteTrip;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    void 여행_상세의_예열_후_SQL_다섯_회를_요청_지표로_기록하고_ID는_태그에_넣지_않는다() throws Exception {
        for (int days : new int[] {1, 3}) {
            int[] counts = new int[days];
            Arrays.fill(counts, 2);
            Trip trip = fixture(counts);
            String bearer = "Bearer " + jwtService.issue(trip.getUser());
            mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                    .andExpect(status().isOk());
            DistributionSummary summary = meterRegistry.get("trova.db.queries.per.request")
                    .tags("uri", "/api/trips/{id}", "method", "GET").summary();
            long beforeCount = summary.count();
            double beforeTotal = summary.totalAmount();
            statistics().clear();
            mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                    .andExpect(status().isOk());
            assertThat(statistics().getPrepareStatementCount()).isEqualTo(5);
            assertThat(summary.count()).isEqualTo(beforeCount + 1);
            assertThat(summary.totalAmount() - beforeTotal).isEqualTo(5);
        }
        assertThat(meterRegistry.find("trova.db.queries.per.request").meters())
                .filteredOn(meter -> meter.getId().getTag("uri").startsWith("/api/trips/"))
                .hasSize(1);
    }

    @Test
    void 컨트롤러_예외와_없는_경로_뒤에도_다음_요청은_영부터_세고_원시_경로를_태그로_만들지_않는다() throws Exception {
        Trip trip = fixture(2);
        String bearer = "Bearer " + jwtService.issue(trip.getUser());
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                .andExpect(status().isOk());
        DistributionSummary summary = meterRegistry.get("trova.db.queries.per.request")
                .tags("uri", "/api/trips/{id}", "method", "GET").summary();

        // 1) 컨트롤러 예외(없는 여행): 같은 템플릿에 표본 하나만 남는다.
        long missingId = 987654321L;
        long beforeError = summary.count();
        mockMvc.perform(get("/api/trips/" + missingId).header("Authorization", bearer))
                .andExpect(status().is4xxClientError());
        assertThat(summary.count()).isEqualTo(beforeError + 1);
        assertNextDetailCountsFromZero(trip, bearer, summary);

        // 2) 매핑 없는 /api 경로 404: 어떤 표본도 늘지 않고 원시 경로 태그도 생기지 않는다.
        long allBefore = totalSamples();
        mockMvc.perform(get("/api/no-such-path/" + missingId).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        assertThat(totalSamples()).isEqualTo(allBefore);
        assertNextDetailCountsFromZero(trip, bearer, summary);

        assertThat(meterRegistry.find("trova.db.queries.per.request").meters())
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTag("uri")).doesNotContain("no-such-path")
                            .doesNotContain(String.valueOf(missingId))
                            .doesNotContain(String.valueOf(trip.getId()));
                });
    }

    private void assertNextDetailCountsFromZero(Trip trip, String bearer, DistributionSummary summary) throws Exception {
        long count = summary.count();
        double total = summary.totalAmount();
        statistics().clear();
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                .andExpect(status().isOk());
        assertThat(summary.count()).isEqualTo(count + 1);
        assertThat(summary.totalAmount() - total).isEqualTo(statistics().getPrepareStatementCount()).isEqualTo(5);
    }

    private long totalSamples() {
        return meterRegistry.find("trova.db.queries.per.request").summaries().stream()
                .mapToLong(DistributionSummary::count).sum();
    }

    private Trip fixture(int... counts) {
        User owner = userRepository.save(new User("google", "detail-count-" + UUID.randomUUID(), "주인", null));
        LocalDate start = LocalDate.of(2026, 12, 1);
        Trip trip = tripRepository.save(new Trip(owner, "여행", start, start.plusDays(counts.length - 1)));
        for (int day = counts.length; day >= 1; day--) {
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, day, start.plusDays(day - 1)));
            for (int visit = counts[day - 1]; visit >= 1; visit--) {
                tripPlaceRepository.save(new TripPlace(itinerary, day + "일차-" + visit, "서울", "cafe",
                        37.5, 127.0, null, null, visit, PlaceSource.NORMAL, null));
            }
        }
        return trip;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long detailStatements(Trip trip, int days) throws Exception {
        String bearer = "Bearer " + jwtService.issue(trip.getUser());
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer)).andExpect(status().isOk());
        statistics().clear();
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(days)));
        return statistics().getPrepareStatementCount();
    }

    @Test
    void 일차가_늘어도_상세와_삭제_영향_SQL_수는_고정이다() throws Exception {
        List<Long> detailCounts = new ArrayList<>();
        List<Long> impactCounts = new ArrayList<>();
        for (int days : new int[] {1, 3, 5, 7}) {
            int[] counts = new int[days];
            Arrays.fill(counts, 2);
            Trip trip = fixture(counts);
            detailCounts.add(detailStatements(trip, days));
            deleteTrip.preview(trip.getId(), 999L);
            statistics().clear();
            assertThat(deleteTrip.preview(trip.getId(), 999L).impact())
                    .isEqualTo("여행 1·일차 " + days + "·장소 " + (days * 2));
            impactCounts.add(statistics().getPrepareStatementCount());
        }
        System.out.println("P1 SQL days=1,3,5,7 detail=" + detailCounts + " impact=" + impactCounts);
        assertThat(detailCounts).containsOnly(detailCounts.getFirst());
        assertThat(impactCounts).containsOnly(impactCounts.getFirst());
    }

    @Test
    void 여행_장소_조회는_정렬과_여행_격리를_지키며_SQL_한_번이다() {
        fixture(3, 3);
        for (int days : new int[] {1, 3, 5, 7}) {
            int[] counts = new int[days];
            Arrays.fill(counts, 2);
            Trip trip = fixture(counts);
            List<String> expected = new ArrayList<>();
            for (int day = 1; day <= days; day++) {
                expected.add(day + "일차-1");
                expected.add(day + "일차-2");
            }
            tripPlaceRepository.findByTripOrderByDayAndVisitOrder(trip);
            statistics().clear();
            List<TripPlace> places = tripPlaceRepository.findByTripOrderByDayAndVisitOrder(trip);
            assertThat(places).extracting(TripPlace::getPlaceName).containsExactlyElementsOf(expected);
            assertThat(places).allSatisfy(place -> {
                assertThat(place.getItinerary().getTrip().getId()).isEqualTo(trip.getId());
                assertThat(place.getItinerary().getTrip().getUser().getId()).isEqualTo(trip.getUser().getId());
            });
            long statements = statistics().getPrepareStatementCount();
            System.out.println("P1 repository days=" + days + " SQL=" + statements);
            assertThat(statements).isEqualTo(1);
        }
    }
    @Test
    void 빈_일차와_일차별_방문_순서와_삭제_영향을_유지한다() throws Exception {
        Trip trip = fixture(2, 0, 3);
        fixture(4, 4);
        mockMvc.perform(get("/api/trips/" + trip.getId()).header("Authorization", "Bearer " + jwtService.issue(trip.getUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(3)))
                .andExpect(jsonPath("$.days[0].day").value(1))
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("1일차-1"))
                .andExpect(jsonPath("$.days[0].places[1].placeName").value("1일차-2"))
                .andExpect(jsonPath("$.days[1].day").value(2))
                .andExpect(jsonPath("$.days[1].places", hasSize(0)))
                .andExpect(jsonPath("$.days[2].day").value(3))
                .andExpect(jsonPath("$.days[2].places[2].placeName").value("3일차-3"));
        assertThat(deleteTrip.preview(trip.getId(), 999L).impact()).isEqualTo("여행 1·일차 3·장소 5");
    }
}
