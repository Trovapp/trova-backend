package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.TransportMode;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripShareLink;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripShareLinkRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.security.SuspendedUsers;
import com.trova.backend.service.TripService;
import com.trova.backend.service.TripShareLinkService;
import com.trova.backend.support.RateLimitWindow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trip-share-link;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.share-link.public-per-minute=3",
        "app.web-app-url=https://app.example.test"
})
class TripShareLinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private TripPlaceRepository tripPlaceRepository;

    @Autowired
    private TripShareLinkRepository tripShareLinkRepository;

    @Autowired
    private TripService tripService;

    @Autowired
    private TripShareLinkService tripShareLinkService;

    @Autowired
    private SuspendedUsers suspendedUsers;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        return oauth2Login()
                .clientRegistration(googleRegistration())
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    private Trip trip(User user, int days) {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        Trip trip = tripRepository.save(new Trip(user, "테스트 여행", startDate, startDate.plusDays(days - 1)));
        for (int day = 1; day <= days; day++) {
            itineraryRepository.save(new Itinerary(trip, day, startDate.plusDays(day - 1)));
        }
        return trip;
    }

    private User owner() {
        return userRepository.save(new User("google", "trip-share-owner", "소유자", null));
    }

    private String createLink(Trip trip) throws Exception {
        String body = mockMvc.perform(post("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(body).contains("\"url\":\"https://app.example.test/t/" + token + "\"");
        return token;
    }

    private RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private TripPlace place(Trip trip) {
        Itinerary day = itineraryRepository.findByTripOrderByDay(trip).get(0);
        TripPlace place = new TripPlace(day, "공유 장소", "부산", "cafe", 35.1, 129.0,
                "051-123-4567", "부산 주소", 1, PlaceSource.NORMAL, null);
        place.applyDetails(null, null, null, "공개하면 안 되는 메모");
        return tripPlaceRepository.save(place);
    }

    @Test
    void 만들면_토큰과_url이_돌아온다() throws Exception {
        Trip trip = trip(owner(), 1);
        mockMvc.perform(get("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNotFound());

        String token = createLink(trip);

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        mockMvc.perform(get("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.url").value("https://app.example.test/t/" + token))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
        assertThat(tripShareLinkRepository.findFirstByTripAndRevokedAtIsNullOrderByCreatedAtAsc(trip).orElseThrow().getToken())
                .isEqualTo(token);
    }

    @Test
    void 두_번_만들면_같은_링크를_돌려준다() throws Exception {
        Trip trip = trip(owner(), 1);
        String token = createLink(trip);

        mockMvc.perform(post("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token));
        assertThat(tripShareLinkRepository.count()).isEqualTo(1);
    }

    @Test
    void 남의_여행은_404() throws Exception {
        Trip trip = trip(owner(), 1);
        createLink(trip);
        userRepository.save(new User("google", "trip-share-other", "다른 사용자", null));

        mockMvc.perform(get("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-other", "다른 사용자")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-other", "다른 사용자")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-other", "다른 사용자")))
                .andExpect(status().isNotFound());
        assertThat(tripShareLinkRepository.findByTripAndRevokedAtIsNull(trip)).isNotEmpty();
    }

    @Test
    void 로그인_없이_만들기는_401() throws Exception {
        Trip trip = trip(owner(), 1);

        mockMvc.perform(post("/api/trips/{id}/share-link", trip.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 공개_조회는_로그인_없이_된다() throws Exception {
        Trip trip = trip(owner(), 2);
        place(trip);
        String token = createLink(trip);

        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("테스트 여행"))
                .andExpect(jsonPath("$.startDate").value("2026-01-01"))
                .andExpect(jsonPath("$.endDate").value("2026-01-02"))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].day").value(1))
                .andExpect(jsonPath("$.days[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("공유 장소"))
                .andExpect(jsonPath("$.days[0].places[0].latitude").value(35.1))
                .andExpect(jsonPath("$.days[0].places[0].longitude").value(129.0))
                .andExpect(jsonPath("$.days[1].day").value(2));
    }

    @Test
    void 공개_응답에_메모와_id가_없다() throws Exception {
        Trip trip = trip(owner(), 1);
        TripPlace place = place(trip);
        place.applyGooglePlaceId("private-google-place-id");
        String token = createLink(trip);

        String body = mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("공유 장소"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("memo", "\"id\"", "source", "googlePlaceId", "user");
    }

    @Test
    void 끈_링크로는_공개_조회가_404() throws Exception {
        Trip trip = trip(owner(), 1);
        String token = createLink(trip);

        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.3")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNotFound());
        assertThat(tripShareLinkRepository.findByTokenAndRevokedAtIsNull(token)).isEmpty();
        assertThat(tripShareLinkRepository.findAll().get(0).getRevokedAt()).isNotNull();
    }

    @Test
    void 끈_뒤_다시_만들면_새_토큰() throws Exception {
        Trip trip = trip(owner(), 1);
        String first = createLink(trip);
        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNoContent());

        String second = createLink(trip);

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/api/public/trips/{token}", first).with(fromIp("192.0.2.4")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/trips/{token}", second).with(fromIp("192.0.2.4")))
                .andExpect(status().isOk());
    }

    @Test
    void 모르는_토큰은_404() throws Exception {
        mockMvc.perform(get("/api/public/trips/unknown-token").with(fromIp("192.0.2.5")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 여행을_지우면_링크도_사라진다() throws Exception {
        User user = owner();
        Trip trip = trip(user, 1);
        createLink(trip);
        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNoContent());
        createLink(trip);
        assertThat(tripShareLinkRepository.count()).isEqualTo(2);

        assertThat(tripService.deleteTrip(user, trip.getId())).isTrue();
        tripRepository.flush();

        assertThat(tripShareLinkRepository.count()).isZero();
        assertThat(tripRepository.findById(trip.getId())).isEmpty();
    }

    @Test
    void 공개_조회_분당_제한을_넘으면_429() throws Exception {
        RateLimitWindow.awaitFreshMinute();
        Trip trip = trip(owner(), 1);
        String token = createLink(trip);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.6")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.6")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.7")))
                .andExpect(status().isOk());
    }

    @Test
    void 켜진_링크가_둘이어도_조회와_끄기가_동작한다() throws Exception {
        Trip trip = trip(owner(), 1);
        tripShareLinkRepository.save(new TripShareLink(trip, "duplicate-first"));
        tripShareLinkRepository.save(new TripShareLink(trip, "duplicate-second"));

        mockMvc.perform(get("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("duplicate-first"));
        mockMvc.perform(delete("/api/trips/{id}/share-link", trip.getId())
                        .with(loginAs("trip-share-owner", "소유자")))
                .andExpect(status().isNoContent());
        for (String token : new String[] {"duplicate-first", "duplicate-second"}) {
            mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.8")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void 정지된_계정의_링크는_공개_조회가_404() throws Exception {
        User user = owner();
        String token = createLink(trip(user, 1));
        suspendedUsers.add(user.getId());
        try {
            mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.9")))
                    .andExpect(status().isNotFound());
        } finally {
            suspendedUsers.remove(user.getId());
        }
    }

    private record PublicTripFixture(Long userId, Long tripId, String token) {
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 공개_조회는_트랜잭션_밖에서도_여행_제목을_읽는다() throws Exception {
        PublicTripFixture fixture = new TransactionTemplate(transactionManager).execute(status -> {
            User user = owner();
            Trip trip = trip(user, 1);
            place(trip);
            String token = tripShareLinkService.createOrGet(trip).link().getToken();
            return new PublicTripFixture(user.getId(), trip.getId(), token);
        });
        assertThat(fixture).isNotNull();
        try {
            mockMvc.perform(get("/api/public/trips/{token}", fixture.token()).with(fromIp("192.0.2.10")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("테스트 여행"))
                    .andExpect(jsonPath("$.days[0].places[0].placeName").value("공유 장소"));
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                User user = userRepository.findById(fixture.userId()).orElseThrow();
                tripService.deleteTrip(user, fixture.tripId());
                userRepository.delete(user);
            });
        }
    }

    @Test
    void 공개_응답은_장소_필드_전체를_담는다() throws Exception {
        Trip trip = trip(owner(), 1);
        TripPlace place = place(trip);
        place.applyDetails(LocalTime.of(10, 0), LocalTime.of(11, 0), TransportMode.WALK, null);
        String token = createLink(trip);

        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.11")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].places[0].placeName").value("공유 장소"))
                .andExpect(jsonPath("$.days[0].places[0].region").value("부산"))
                .andExpect(jsonPath("$.days[0].places[0].category").value("cafe"))
                .andExpect(jsonPath("$.days[0].places[0].latitude").value(35.1))
                .andExpect(jsonPath("$.days[0].places[0].longitude").value(129.0))
                .andExpect(jsonPath("$.days[0].places[0].address").value("부산 주소"))
                .andExpect(jsonPath("$.days[0].places[0].phone").value("051-123-4567"))
                .andExpect(jsonPath("$.days[0].places[0].visitOrder").value(1))
                .andExpect(jsonPath("$.days[0].places[0].visitStartTime").value("10:00:00"))
                .andExpect(jsonPath("$.days[0].places[0].visitEndTime").value("11:00:00"))
                .andExpect(jsonPath("$.days[0].places[0].arrivalTransportMode").value("WALK"));
    }

    @Test
    void 모르는_토큰_반복_조회도_분당_제한에_센다() throws Exception {
        RateLimitWindow.awaitFreshMinute();
        Trip trip = trip(owner(), 1);
        String token = createLink(trip);

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(get("/api/public/trips/{token}", "unknown-" + i).with(fromIp("192.0.2.12")))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/public/trips/{token}", token).with(fromIp("192.0.2.12")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
    }
}
