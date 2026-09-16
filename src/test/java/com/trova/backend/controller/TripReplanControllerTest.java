package com.trova.backend.controller;

import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.recommendation.AlternativeFilter;
import com.trova.backend.recommendation.AlternativeFinderService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripPlaceRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TripReplanControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;

    // 대안 조회 자체(구글 Places/개인화)는 이 테스트 범위 밖 — TripReplanGraphTest가
    // 이미 백트래킹/타겟 선별을 검증했으므로, 여기서는 컨트롤러의 인증/소유권/응답
    // 변환만 검증한다.
    @MockitoBean private AlternativeFinderService alternativeFinderService;

    private User me;
    private Trip trip;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "replan1", "재구성유저", null));
        trip = tripRepository.save(new Trip(me, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
    }

    private RequestPostProcessor loginAs(String sub, String name) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login()
                .clientRegistration(registration)
                .attributes(attrs -> {
                    attrs.put("sub", sub);
                    attrs.put("name", name);
                    attrs.put("picture", "https://example.com/p.jpg");
                });
    }

    @Test
    void indoorOnly가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 남의_여행이면_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

        mockMvc.perform(post("/api/trips/" + otherTrip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 실외_장소가_실내_대안으로_재구성되면_응답에_담긴다() throws Exception {
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 1, 1)));
        TripPlace outdoorPlace = tripPlaceRepository.save(new TripPlace(
                itinerary, "야외공원", "서울", "park", 37.50, 127.00, null, null, 1, PlaceSource.NORMAL, null));
        outdoorPlace.applySpace("OUTDOOR");
        tripPlaceRepository.save(outdoorPlace);

        AlternativeCandidate indoorCandidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        when(alternativeFinderService.findAlternatives(any(), eq(outdoorPlace.getId()), any(AlternativeFilter.class)))
                .thenReturn(Optional.of(List.of(indoorCandidate)));

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replaced[0].tripPlaceId").value(outdoorPlace.getId()))
                .andExpect(jsonPath("$.replaced[0].originalName").value("야외공원"))
                .andExpect(jsonPath("$.replaced[0].candidate.placeId").value(99))
                .andExpect(jsonPath("$.failedTripPlaceIds.length()").value(0));
    }
}
