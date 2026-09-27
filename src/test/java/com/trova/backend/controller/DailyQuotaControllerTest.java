package com.trova.backend.controller;

import com.trova.backend.entity.User;
import com.trova.backend.recommendation.GooglePlacesApiClient;
import com.trova.backend.recommendation.GooglePlacesNearbySearchResponse;
import com.trova.backend.recommendation.PlaceEmbeddingService;
import com.trova.backend.repository.UserRepository;
import com.trova.backend.service.PlaceExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"app.quota.shares-per-day=2", "app.quota.place-calls-per-day=2"})
class DailyQuotaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    // 실제 영상 처리·외부 API는 부르지 않는다 — 한도 판단만 본다.
    @MockitoBean
    private PlaceExtractionService placeExtractionService;

    @MockitoBean
    private GooglePlacesApiClient googlePlacesApiClient;

    @MockitoBean
    private PlaceEmbeddingService placeEmbeddingService;

    private RequestPostProcessor loginAs(String sub) {
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .build();
        return oauth2Login().clientRegistration(google).attributes(attrs -> {
            attrs.put("sub", sub);
            attrs.put("name", sub);
            attrs.put("picture", "https://example.com/p.jpg");
        });
    }

    @Test
    void 링크는_하루_한도까지만_제출할_수_있다() throws Exception {
        userRepository.save(new User("google", "quota-share", "한도유저", null));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/shares").with(loginAs("quota-share"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://youtu.be/quota" + i + "\"}"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/api/shares").with(loginAs("quota-share"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://youtu.be/quota-over\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("오늘은 링크를 2개까지 추가할 수 있어요. 내일 다시 시도해주세요."));
    }

    @Test
    void 장소_검색은_하루_한도까지만_호출할_수_있고_다른_사용자와_따로_센다() throws Exception {
        when(googlePlacesApiClient.searchText(anyString())).thenReturn(new GooglePlacesNearbySearchResponse(List.of()));
        when(googlePlacesApiClient.searchNearby(anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(new GooglePlacesNearbySearchResponse(List.of()));
        userRepository.save(new User("google", "quota-place", "장소유저", null));
        userRepository.save(new User("google", "quota-place-other", "다른유저", null));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/places/search").param("query", "카페").with(loginAs("quota-place")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/places/search").param("query", "카페").with(loginAs("quota-place")))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/places/search").param("query", "카페").with(loginAs("quota-place-other")))
                .andExpect(status().isOk());
    }
}
