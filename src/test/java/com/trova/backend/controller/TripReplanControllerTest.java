package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripReplanJob;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.AlternativeCandidate;
import com.trova.backend.replan.TripReplanGraph;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.TripRepository;
import com.trova.backend.repository.TripReplanJobRepository;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.TripReplanJobService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired private TripReplanJobRepository tripReplanJobRepository;

    // 실제 그래프 실행(TripReplanGraph)은 TripReplanGraphTest가, JSON 왕복은
    // TripReplanJobServiceTest가 이미 검증했다. 여기서는 컨트롤러의 인증/소유권/
    // 중복 제출 방지/상태별 응답 변환만 검증하므로 비동기 오케스트레이터 자체를
    // 목으로 대체해 실제 백그라운드 처리가 일어나지 않게 한다.
    @MockitoBean private TripReplanJobService tripReplanJobService;

    private final ObjectMapper mapper = new ObjectMapper();

    private User me;
    private Trip trip;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "replan1", "재구성유저", null));
        trip = tripRepository.save(new Trip(me, "테스트 여행", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
        doNothing().when(tripReplanJobService).process(anyLong());
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
    void allPlaces만_있어도_202와_jobId를_반환한다() throws Exception {
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allPlaces\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists());

        List<TripReplanJob> jobs = tripReplanJobRepository.findAll();
        org.assertj.core.api.Assertions.assertThat(jobs).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(jobs.get(0).isAllPlaces()).isTrue();
        org.assertj.core.api.Assertions.assertThat(jobs.get(0).isIndoorOnly()).isFalse();
    }

    @Test
    void 남의_여행이면_POST에서_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

        mockMvc.perform(post("/api/trips/" + otherTrip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 정상_요청이면_202와_jobId를_반환하고_비동기_처리를_시작한다() throws Exception {
        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists());

        List<TripReplanJob> jobs = tripReplanJobRepository.findAll();
        org.assertj.core.api.Assertions.assertThat(jobs).hasSize(1);
        verify(tripReplanJobService).process(jobs.get(0).getId());
    }

    @Test
    void 같은_조건으로_중복_제출하면_기존_jobId를_재사용한다() throws Exception {
        TripReplanJob existing = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));

        mockMvc.perform(post("/api/trips/" + trip.getId() + "/replan")
                        .with(loginAs("replan1", "재구성유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indoorOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(existing.getId()));

        org.assertj.core.api.Assertions.assertThat(tripReplanJobRepository.findAll()).hasSize(1);
        verify(tripReplanJobService, never()).process(anyLong());
    }

    @Test
    void 남의_작업을_폴링하면_404() throws Exception {
        User other = userRepository.save(new User("google", "other2", "다른유저2", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행2", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
        TripReplanJob otherJob = tripReplanJobRepository.save(new TripReplanJob(other, otherTrip, true));

        mockMvc.perform(get("/api/trips/" + otherTrip.getId() + "/replan/" + otherJob.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isNotFound());
    }

    @Test
    void PROCESSING_상태를_폴링하면_결과없이_진행률만_담긴다() throws Exception {
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.updateProgress(2, 5);
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.completedTargets").value(2))
                .andExpect(jsonPath("$.totalTargets").value(5))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void DONE_상태를_폴링하면_결과가_담긴다() throws Exception {
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, LocalDate.of(2026, 1, 1)));
        AlternativeCandidate candidate = new AlternativeCandidate(
                99L, "g-99", "실내카페", "cafe", 4.7, 200, 37.501, 127.001, "서울",
                null, null, false, null, null);
        TripReplanGraph.ReplanMatch match = new TripReplanGraph.ReplanMatch(1L, "야외공원", candidate);
        TripReplanGraph.ReplanOutcome outcome = new TripReplanGraph.ReplanOutcome(List.of(match), List.of(2L));
        String resultJson = mapper.writeValueAsString(outcome);

        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.markDone(resultJson);
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.replaced[0].tripPlaceId").value(1))
                .andExpect(jsonPath("$.result.replaced[0].originalName").value("야외공원"))
                .andExpect(jsonPath("$.result.replaced[0].candidate.placeId").value(99))
                .andExpect(jsonPath("$.result.failedTripPlaceIds[0]").value(2))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void FAILED_상태를_폴링하면_에러메시지가_담긴다() throws Exception {
        TripReplanJob job = tripReplanJobRepository.save(new TripReplanJob(me, trip, true));
        job.markProcessing();
        job.markFailed("그래프 실행 중 예외 발생");
        tripReplanJobRepository.save(job);

        mockMvc.perform(get("/api/trips/" + trip.getId() + "/replan/" + job.getId())
                        .with(loginAs("replan1", "재구성유저")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value("그래프 실행 중 예외 발생"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
