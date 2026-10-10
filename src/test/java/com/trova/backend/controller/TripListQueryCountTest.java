package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 여행 목록(#153)의 DB 쿼리 수가 일차 수와 상관없이 같은지 잰다. 고치기 전에는 여행 장소를 엔티티로 읽으면서
 * 기본 EAGER 연관(일차 → 여행 → 사용자) 때문에 장소가 있는 일차마다 SELECT가 하나씩 더 나갔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class TripListQueryCountTest {

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
    private EntityManager entityManager;

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

    /** 일차마다 장소 2개씩 있는 여행 하나를 만든다. */
    private void tripWithPlaces(User user, int days) {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        Trip trip = tripRepository.save(new Trip(user, "쿼리 여행", startDate, startDate.plusDays(days - 1)));
        for (int day = 1; day <= days; day++) {
            Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, day, startDate.plusDays(day - 1)));
            for (int order = 0; order < 2; order++) {
                tripPlaceRepository.save(new TripPlace(itinerary, "장소" + day + "-" + order, "제주", "cafe",
                        33.4, 126.5, null, null, order, PlaceSource.NORMAL, null));
            }
        }
    }

    private long queriesForTripList(String sub, String name) throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mockMvc.perform(get("/api/trips").with(loginAs(sub, name))).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    void 여행_목록의_쿼리_수는_일차_수와_상관없이_같다() throws Exception {
        User one = userRepository.save(new User("google", "tripq1", "목록쿼리1", null));
        tripWithPlaces(one, 1);
        long withOneDay = queriesForTripList("tripq1", "목록쿼리1");

        User many = userRepository.save(new User("google", "tripq2", "목록쿼리2", null));
        tripWithPlaces(many, 6);
        tripWithPlaces(many, 4);
        long withTenDays = queriesForTripList("tripq2", "목록쿼리2");

        System.out.println("[#153] 여행 목록 쿼리 수: 일차 1개=" + withOneDay + ", 일차 10개(여행 2개)=" + withTenDays);
        assertThat(withTenDays).isEqualTo(withOneDay);
    }
}
