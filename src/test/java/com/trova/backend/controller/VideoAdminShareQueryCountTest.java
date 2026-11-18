package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.ProcessingJob;
import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.SourcePlatform;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.ProcessingJobRepository;
import com.trova.backend.repository.SavedPlaceRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.JwtService;
import com.trova.backend.service.TripShareLinkService;
import com.trova.backend.support.AdminWebAuth;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 운영처럼 요청마다 독립 영속성 컨텍스트를 사용한다(테스트 트랜잭션 없음).
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:p2-query-count;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "app.admin.users=google:p2-admin"})
class VideoAdminShareQueryCountTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private UserRepository users;
    @Autowired private ProcessingJobRepository jobs;
    @Autowired private SavedPlaceRepository savedPlaces;
    @Autowired private TripRepository trips;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private TripPlaceRepository tripPlaces;
    @Autowired private TripShareLinkService shareLinks;

    private User user() {
        return users.save(new User("google", "p2-" + UUID.randomUUID(), "사용자", null));
    }

    private String url() {
        return "https://www.youtube.com/watch?v=" + UUID.randomUUID().toString().substring(0, 11);
    }

    private ProcessingJob job(User user, String url, String failure) {
        ProcessingJob job = new ProcessingJob(user, url, SourcePlatform.YOUTUBE);
        if (failure != null) job.markFailed(failure);
        job = jobs.save(job);
        savedPlaces.save(new SavedPlace(job, user, "장소", "서울", "cafe", 37.5, 127.0));
        return job;
    }

    private Trip trip(User user, int... counts) {
        LocalDate start = LocalDate.of(2026, 12, 1);
        Trip trip = trips.save(new Trip(user, "여행", start, start.plusDays(counts.length - 1)));
        for (int day = counts.length; day >= 1; day--) {
            Itinerary itinerary = itineraries.save(new Itinerary(trip, day, start.plusDays(day - 1)));
            for (int visit = counts[day - 1]; visit >= 1; visit--) {
                tripPlaces.save(new TripPlace(itinerary, day + "일차-" + visit, "서울", "cafe",
                        37.5, 127.0, null, null, visit, PlaceSource.NORMAL, null));
            }
        }
        return trip;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long statements(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isOk());
        statistics().clear();
        mockMvc.perform(request).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }

    @Test
    void 같은_영상의_작업이_늘어도_SQL_수는_고정이다() throws Exception {
        List<Long> counts = new ArrayList<>();
        for (int count : new int[] {1, 5}) {
            User owner = user();
            String url = url();
            ProcessingJob first = job(owner, url, null);
            for (int i = 1; i < count; i++) job(owner, url, null);
            Trip trip = trip(owner, 0);
            SavedPlace saved = savedPlaces.findByProcessingJob(first).getFirst();
            tripPlaces.save(new TripPlace(itineraries.findByTripOrderByDay(trip).getFirst(), "영상 장소",
                    "서울", "cafe", 37.5, 127.0, null, null, 1, PlaceSource.VIDEO, saved.getId()));
            MockHttpServletRequestBuilder request = get("/api/places/videos/" + first.getId() + "/trip")
                    .header("Authorization", "Bearer " + jwtService.issue(owner));
            counts.add(statements(request));
            mockMvc.perform(request).andExpect(jsonPath("$.id").value(trip.getId()))
                    .andExpect(jsonPath("$.title").value("여행"));
        }
        System.out.println("P2 video jobs=1,5 SQL=" + counts);
        assertThat(counts).containsOnly(counts.getFirst());
    }

    @Test
    void 관리자_상세의_실패_작업이_늘어도_SQL_수는_고정이다() throws Exception {
        RequestPostProcessor admin = AdminWebAuth.adminWebToken(jwtService, users, "p2-admin");
        List<Long> counts = new ArrayList<>();
        for (int count : new int[] {1, 5}) {
            User owner = user();
            for (int i = 0; i < count; i++) job(owner, url(), ProcessingJob.AI_QUOTA_MESSAGE);
            MockHttpServletRequestBuilder request = get("/api/admin/users/" + owner.getId()).with(admin);
            counts.add(statements(request));
            mockMvc.perform(request).andExpect(jsonPath("$.recentJobs", hasSize(count)))
                    .andExpect(jsonPath("$.recentJobs[0].retryable").value(true));
        }
        System.out.println("P2 admin jobs=1,5 SQL=" + counts);
        assertThat(counts).containsOnly(counts.getFirst());
    }

    @Test
    void 재시도_판단은_같은_사용자의_최신_작업과_실패_이유를_유지한다() throws Exception {
        User owner = user();
        String duplicate = url();
        job(owner, duplicate, ProcessingJob.AI_QUOTA_MESSAGE);
        job(owner, duplicate, ProcessingJob.AI_QUOTA_MESSAGE);
        job(owner, url(), ProcessingJob.NO_PLACES_MESSAGE);
        String otherUserUrl = url();
        job(owner, otherUserUrl, ProcessingJob.AI_QUOTA_MESSAGE);
        job(user(), otherUserUrl, null);
        mockMvc.perform(get("/api/admin/users/" + owner.getId())
                        .with(AdminWebAuth.adminWebToken(jwtService, users, "p2-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentJobs[0].retryable").value(true))
                .andExpect(jsonPath("$.recentJobs[1].retryable").value(false))
                .andExpect(jsonPath("$.recentJobs[2].retryable").value(true))
                .andExpect(jsonPath("$.recentJobs[3].retryable").value(false));
    }

    @Test
    void 최근_10개_경계에서도_새_처리중_작업이_이전_실패의_재시도를_막는다() throws Exception {
        User owner = user();
        String repeated = url();
        job(owner, repeated, ProcessingJob.AI_QUOTA_MESSAGE);
        job(owner, repeated, ProcessingJob.AI_QUOTA_MESSAGE);
        for (int i = 0; i < 8; i++) job(owner, url(), ProcessingJob.AI_QUOTA_MESSAGE);
        job(owner, repeated, null);
        mockMvc.perform(get("/api/admin/users/" + owner.getId())
                        .with(AdminWebAuth.adminWebToken(jwtService, users, "p2-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentJobs", hasSize(10)))
                .andExpect(jsonPath("$.recentJobs[0].retryable").value(false))
                .andExpect(jsonPath("$.recentJobs[1].retryable").value(true))
                .andExpect(jsonPath("$.recentJobs[9].retryable").value(false));
    }

    @Test
    void 공개_여행의_일차가_늘어도_SQL_수는_고정이다() throws Exception {
        List<Long> counts = new ArrayList<>();
        for (int days : new int[] {1, 3, 5, 7}) {
            int[] places = new int[days];
            Arrays.fill(places, 2);
            Trip trip = trip(user(), places);
            String token = shareLinks.createOrGet(trip).link().getToken();
            counts.add(statements(get("/api/public/trips/" + token)));
        }
        System.out.println("P2 public days=1,3,5,7 SQL=" + counts);
        assertThat(counts).containsOnly(counts.getFirst());
    }

    @Test
    void 공개_여행은_빈_일차와_정렬과_여행_격리를_유지한다() throws Exception {
        Trip trip = trip(user(), 2, 0, 3);
        trip(user(), 4, 4);
        String token = shareLinks.createOrGet(trip).link().getToken();
        mockMvc.perform(get("/api/public/trips/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(3)))
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("1일차-1"))
                .andExpect(jsonPath("$.days[0].places[1].placeName").value("1일차-2"))
                .andExpect(jsonPath("$.days[1].places", hasSize(0)))
                .andExpect(jsonPath("$.days[2].places[2].placeName").value("3일차-3"));
    }

    @Test
    void 아직_없는_여행과_공유_링크는_파싱할_수_있는_JSON_null이다() throws Exception {
        User owner = user();
        ProcessingJob job = job(owner, url(), null);
        Trip trip = trip(owner, 0);
        String bearer = "Bearer " + jwtService.issue(owner);
        for (String path : List.of("/api/places/videos/" + job.getId() + "/trip",
                "/api/trips/" + trip.getId() + "/share-link")) {
            mockMvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(content().string("null"));
        }
        shareLinks.createOrGet(trip);
        shareLinks.revoke(trip);
        mockMvc.perform(get("/api/trips/" + trip.getId() + "/share-link").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(content().string("null"));
    }

    @Test
    void 타인과_존재하지_않는_자원은_404이고_미인증은_401이다() throws Exception {
        User owner = user();
        ProcessingJob job = job(owner, url(), null);
        Trip trip = trip(owner, 0);
        String bearer = "Bearer " + jwtService.issue(user());
        for (String path : List.of("/api/places/videos/" + job.getId() + "/trip",
                "/api/trips/" + trip.getId() + "/share-link", "/api/places/videos/9223372036854775807/trip",
                "/api/trips/9223372036854775807/share-link")) {
            mockMvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isNotFound());
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/admin/users/" + owner.getId()).header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }
}
