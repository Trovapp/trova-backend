package com.trova.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trova.backend.conversation.GeminiChatClient;
import com.trova.backend.entity.Itinerary;
import com.trova.backend.entity.Place;
import com.trova.backend.entity.PlaceSource;
import com.trova.backend.entity.Trip;
import com.trova.backend.entity.TripPlace;
import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PersonalizationService;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.ItineraryRepository;
import com.trova.backend.repository.PlaceRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConversationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private TripPlaceRepository tripPlaceRepository;
    @Autowired private PlaceRepository placeRepository;

    // embedding 컬럼은 pgvector 타입이라 H2 테스트 DB 스키마에 없다 — 다른 컨트롤러
    // 테스트(TripControllerTest)와 같은 이유로 목 처리한다.
    @MockitoBean private PlaceEmbeddingService placeEmbeddingService;
    @MockitoBean private PersonalizationService personalizationService;
    @MockitoBean private GooglePlacesApiClient googlePlacesApiClient;
    // Gemini 실호출은 테스트 스위트에서 하지 않는다(무료 티어 한도 보존 + 결정성).
    @MockitoBean private GeminiChatClient geminiChatClient;

    private User me;
    private Trip trip;
    private TripPlace place;

    @BeforeEach
    void setUp() {
        me = userRepository.save(new User("google", "conv1", "대화유저", null));
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        trip = tripRepository.save(new Trip(me, "테스트 여행", startDate, startDate));
        Itinerary itinerary = itineraryRepository.save(new Itinerary(trip, 1, startDate));
        place = tripPlaceRepository.save(new TripPlace(
                itinerary, "경복궁", "서울", "landmark", 37.58, 126.97, null, null, 1, PlaceSource.NORMAL, null));
    }

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

    @Test
    void 첫_메시지로_세션이_생성되고_텍스트만_오면_답변만_온다() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요! 뭘 도와드릴까요?"));

        mockMvc.perform(post("/api/conversations/s1/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("안녕하세요! 뭘 도와드릴까요?"))
                .andExpect(jsonPath("$.turnCount").value(1))
                .andExpect(jsonPath("$.turnLimitReached").value(false));
    }

    @Test
    void tripPlaceId와_day를_둘다_안주면_400() throws Exception {
        mockMvc.perform(post("/api/conversations/s2/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d}".formatted(trip.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 소유하지_않은_tripPlaceId면_404() throws Exception {
        User other = userRepository.save(new User("google", "other", "다른유저", null));
        Trip otherTrip = tripRepository.save(new Trip(other, "다른 여행", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
        Itinerary otherItinerary = itineraryRepository.save(new Itinerary(otherTrip, 1, LocalDate.of(2026, 2, 1)));
        TripPlace otherPlace = tripPlaceRepository.save(new TripPlace(
                otherItinerary, "남산타워", "서울", "landmark", 37.55, 126.98, null, null, 1, PlaceSource.NORMAL, null));

        mockMvc.perform(post("/api/conversations/s3/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(otherTrip.getId(), otherPlace.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void _300자_넘는_메시지는_400() throws Exception {
        String longMessage = "가".repeat(301);
        mockMvc.perform(post("/api/conversations/s4/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"%s\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(longMessage, trip.getId(), place.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void _10턴을_넘으면_11번째_요청은_turnLimitReached이다() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "답변"));

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/conversations/s5/messages")
                            .with(loginAs("conv1", "대화유저"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"질문%d\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                    .formatted(i, trip.getId(), place.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turnLimitReached").value(false));
        }

        mockMvc.perform(post("/api/conversations/s5/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"한번더\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnLimitReached").value(true));
    }

    @Test
    void 다른_유저가_같은_세션에_메시지를_보내면_404() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요!"));

        // 첫 유저가 세션을 만든다.
        mockMvc.perform(post("/api/conversations/s6/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk());

        userRepository.save(new User("google", "intruder", "침입자", null));

        // 같은 sessionId로 다른(인증된) 유저가 들어오면 세션 존재 여부조차 드러내지
        // 않고 404여야 한다 — 컨텍스트 필드가 없어도 소유권 검사가 먼저 걸려야 한다.
        mockMvc.perform(post("/api/conversations/s6/messages")
                        .with(loginAs("intruder", "침입자"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"몰래 들어옴\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 다른_유저가_같은_세션을_종료하려하면_404이고_세션은_삭제되지_않는다() throws Exception {
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "안녕하세요!"));

        mockMvc.perform(post("/api/conversations/s7/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk());

        userRepository.save(new User("google", "intruder2", "침입자2", null));

        mockMvc.perform(delete("/api/conversations/s7")
                        .with(loginAs("intruder2", "침입자2")))
                .andExpect(status().isNotFound());

        // 세션이 삭제되지 않았으므로 원래 소유자는 여전히 대화를 이어갈 수 있어야 한다.
        mockMvc.perform(post("/api/conversations/s7/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"계속\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnCount").value(2));
    }

    @Test
    void gap_컨텍스트_요청에_tripId가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/conversations/s8/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕\",\"day\":1,\"gapBeforePlaceId\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void functionCall이_오면_실제_AlternativeFinderService_경로로_candidates가_응답에_담긴다() throws Exception {
        var raw = new GooglePlacesNearbySearchResponse.Place(
                "gp-conv-1", new GooglePlacesNearbySearchResponse.Place.DisplayName("대안카페"),
                List.of("cafe"), 4.3, 50, "PRICE_LEVEL_MODERATE",
                new GooglePlacesNearbySearchResponse.Place.Location(37.581, 126.971), "서울 어딘가");
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of(raw)));
        when(personalizationService.retrieveAndScore(any(), any()))
                .thenReturn(new PersonalizationService.PersonalizationResult(0.0, List.of()));

        var functionCall = new GeminiChatClient.FunctionCall("find_alternatives", Map.of(), "sig");
        when(geminiChatClient.sendMessage(any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(functionCall, null));
        when(geminiChatClient.sendFunctionResult(any(), any(), any(), any(), any()))
                .thenReturn(new GeminiChatClient.ChatResult(null, "대안카페를 추천해요"));

        String responseJson = mockMvc.perform(post("/api/conversations/s9/messages")
                        .with(loginAs("conv1", "대화유저"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"카페 찾아줘\",\"tripId\":%d,\"tripPlaceId\":%d}"
                                .formatted(trip.getId(), place.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("대안카페를 추천해요"))
                .andExpect(jsonPath("$.candidates[0].name").value("대안카페"))
                .andExpect(jsonPath("$.candidates[0].googlePlaceId").value("gp-conv-1"))
                .andReturn().getResponse().getContentAsString();

        // candidates[0].placeId가 실제 PlaceCatalogService.upsertAll이 DB에 저장한
        // 행의 id와 같아야 한다 — 실 서비스 경로를 탔다는 증거.
        Place savedPlace = placeRepository.findByGooglePlaceIdIn(List.of("gp-conv-1")).get(0);
        long candidatePlaceId = new ObjectMapper().readTree(responseJson).at("/candidates/0/placeId").asLong();
        assertThat(candidatePlaceId).isEqualTo(savedPlace.getId());
    }
}
